package viewforge.packages.compose.codegen

import androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import viewforge.packages.compose.targets.ComposeEntryPoints
import viewforge.project.ProjectCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The **Android** compile gate (#219, M11, ADR-038): the sibling of [CompilationTest] for generated
 * `androidMain` output. #218 emits a `MainActivity` (`ComponentActivity` + `setContent`) alongside the
 * shared `commonMain` screens, but nothing has ever compiled it — string/structural tests pass on code
 * that may not resolve. This feeds the *actual* emitter output — the generated screen(s), the `Theme`
 * wrapper, and [ComposeEntryPoints.androidMainActivity] — to the real Kotlin compiler and requires
 * `ExitCode.OK`.
 *
 * Mirroring ADR-018/[CompilationTest], compilation is **in-process** via kctfork with NO Android SDK:
 * `inheritClassPath` supplies the desktop Compose artifacts, which publish the very same
 * `androidx.compose.*` symbols the shared screens use (Compose Multiplatform aliases
 * `org.jetbrains.compose.*` → `androidx.compose.*`); the only Android-specific symbols in the generated
 * output — `android.os.Bundle`, `androidx.activity.ComponentActivity`, `androidx.activity.compose.setContent`
 * and their transitive `androidx.*` supertypes — come from the `androidCompileGate` classpath the Gradle
 * build resolves (compose/activity AARs flattened to jars + the Robolectric `android-all` stub) and passes
 * in via the `viewforge.android.gate.classpath` system property. So the gate runs on the SDK-less
 * `ct109-runner` beside `codegen-verify`, keeping the required check on Forgejo.
 *
 * The fixtures are **image-free**: `painterResource(String)` (ADR-021) is desktop-only, so image-bearing
 * screens do not yet cross to Android — that is #223. Both a themed (`Demo`, exercises the `AppTheme`
 * wrapper) and a theme-less (`RowBox`, exercises the `MaterialTheme` fallback) project are compiled.
 */
@OptIn(ExperimentalCompilerApi::class)
class AndroidCompilationTest {
    /** Android-only classpath (android-all stub + activity/compose AAR classes) provided by the Gradle build. */
    private val androidGateClasspath: List<File> by lazy {
        val raw = System.getProperty("viewforge.android.gate.classpath")
        requireNotNull(raw) {
            "viewforge.android.gate.classpath not set — the Android compile gate must run under Gradle " +
                "(`:packages:compose:test`), which resolves the androidCompileGate configuration and passes it in."
        }
        raw.split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
    }

    @Test
    fun `themed androidMain output compiles against compose-android, activity, and the android stub`() {
        assertCompiles(androidSources("Demo"), "themed Android output")
    }

    @Test
    fun `theme-less androidMain output falls back to MaterialTheme and compiles`() {
        assertCompiles(androidSources("RowBox"), "theme-less Android output")
    }

    /** The exact files #218's [viewforge.packages.compose.targets.MultiplatformExporter] routes to Android. */
    private fun androidSources(fixture: String): List<SourceFile> {
        val text = requireNotNull(javaClass.getResourceAsStream("/golden/$fixture.vforge")) {
            "missing fixture /golden/$fixture.vforge"
        }.bufferedReader().readText()
        val project = ProjectCodec.decode(text)
        val gen = ComposeCodeGenerator()

        val sources = mutableListOf<SourceFile>()
        gen.generate(project).forEach { sources += SourceFile.kotlin(it.path, it.content) }
        val theme = gen.generateTheme(project)
        theme?.let { sources += SourceFile.kotlin("Theme.kt", it) }
        sources += SourceFile.kotlin(
            ComposeEntryPoints.MAIN_ACTIVITY_KT,
            ComposeEntryPoints.androidMainActivity(project, themed = theme != null),
        )
        return sources
    }

    private fun assertCompiles(sources: List<SourceFile>, label: String) {
        val logs = ByteArrayOutputStream()
        val result = KotlinCompilation().apply {
            this.sources = sources
            inheritClassPath = true
            classpaths = androidGateClasspath
            compilerPluginRegistrars = listOf(ComposePluginRegistrar())
            messageOutputStream = PrintStream(logs)
            verbose = false
        }.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "$label did not compile:\n$logs")
    }
}

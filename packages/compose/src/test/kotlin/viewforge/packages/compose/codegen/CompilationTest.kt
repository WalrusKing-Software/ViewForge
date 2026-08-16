package viewforge.packages.compose.codegen

import androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import viewforge.packages.compose.targets.DesktopExporter
import viewforge.project.ProjectCodec
import viewforge.project.TextFile
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The codegen compile gate (G2/GC-6, TECHNICAL_NOTES §14): string-equality golden tests can pass on
 * output that does not actually compile, so every golden fixture's generated source is fed to the
 * real Kotlin compiler — with the Compose compiler plugin registered — and must reach `ExitCode.OK`.
 * This is the highest-value check in the suite and the reason `codegen-verify.yml` runs
 * `:packages:compose:test`.
 *
 * Compilation is in-process via kotlin-compile-testing (kctfork); `inheritClassPath` puts the real
 * Compose runtime/foundation/material3 on the compiler classpath, so `@Composable`, `Modifier`,
 * `MaterialTheme` etc. resolve exactly as they would in a user project.
 */
@OptIn(ExperimentalCompilerApi::class)
class CompilationTest {
    private val fixtures =
        listOf(
            "Demo", "RowBox", "Modifiers", "ModifierOrder", "Weight", "Lazy", "Image",
            "Containers", "Toggles", "Buttons", "Indicators", "Icons", "TextFields", "AppBars", "Scaffold",
            "TextStyling", "ButtonStates", "ImageAdjust", "TextSpacing", "TextEmphasis", "ButtonStyling",
            "ButtonShape", "Gallery",
        )

    @Test
    fun `generated golden output compiles against Compose`() {
        val sources = fixtures.map { name ->
            val text = requireNotNull(javaClass.getResourceAsStream("/golden/$name.vforge")).bufferedReader().readText()
            val generated = ComposeCodeGenerator().generate(ProjectCodec.decode(text)).single()
            SourceFile.kotlin(generated.path, generated.content)
        }
        assertCompiles(sources, "generated Compose code did not compile")
    }

    @Test
    fun `generated user component compiles with the screen that instances it`() {
        // A screen calls PrimaryButton(...) and the component is emitted as its own composable; compiled
        // together (same package), the instance call must resolve — the reference model (ADR-024, D7).
        val text = requireNotNull(
            javaClass.getResourceAsStream("/golden/ReusableComponent.vforge"),
        ).bufferedReader().readText()
        val files = ComposeCodeGenerator().generate(ProjectCodec.decode(text))
        val sources = files.map { SourceFile.kotlin(it.path, it.content) }
        assertCompiles(sources, "generated user component did not compile with its screen")
    }

    @Test
    fun `generated parameterized component compiles with the screen that instances it`() {
        // A component with typed parameters (parameters slice 2) and a screen that calls it with
        // argument values must compile together: the ParamRef bodies resolve to the fn parameters and
        // each call's named args match the generated signature (ADR-028).
        val text = requireNotNull(
            javaClass.getResourceAsStream("/golden/ParameterizedComponent.vforge"),
        ).bufferedReader().readText()
        val files = ComposeCodeGenerator().generate(ProjectCodec.decode(text))
        val sources = files.map { SourceFile.kotlin(it.path, it.content) }
        assertCompiles(sources, "generated parameterized component did not compile with its screen")
    }

    @Test
    fun `generated theme wrapper compiles with its screen`() {
        // The AppTheme wrapper (H4) and a screen that emits an inline TextStyle for a custom typography
        // token must compile against real Compose/Material3 — the H4 half of the compile gate (M8).
        val text = requireNotNull(
            javaClass.getResourceAsStream("/golden/ThemeWrapper.vforge"),
        ).bufferedReader().readText()
        val project = ProjectCodec.decode(text)
        val theme = requireNotNull(ComposeCodeGenerator().generateTheme(project)) { "theme should be emitted" }
        val screen = ComposeCodeGenerator().generate(project).single()
        val sources = listOf(
            SourceFile.kotlin("Theme.kt", theme),
            SourceFile.kotlin(screen.path, screen.content),
        )
        assertCompiles(sources, "generated theme wrapper did not compile")
    }

    @Test
    fun `formatted export output still compiles`() {
        // The G7 formatting pass (ADR-019) only removes redundant `public`; prove it never produces
        // something that fails to compile — the whole point of the export path (G2/GC-6).
        val sources = fixtures.map { name ->
            val text = requireNotNull(javaClass.getResourceAsStream("/golden/$name.vforge")).bufferedReader().readText()
            val exported = DesktopExporter.looseFiles(ProjectCodec.decode(text)).single() as TextFile
            SourceFile.kotlin(exported.path, exported.content)
        }
        assertCompiles(sources, "formatted export output did not compile")
    }

    private fun assertCompiles(sources: List<SourceFile>, message: String) {
        val logs = ByteArrayOutputStream()
        val result = KotlinCompilation().apply {
            this.sources = sources
            inheritClassPath = true
            compilerPluginRegistrars = listOf(ComposePluginRegistrar())
            messageOutputStream = PrintStream(logs)
            verbose = false
        }.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "$message:\n$logs")
    }
}

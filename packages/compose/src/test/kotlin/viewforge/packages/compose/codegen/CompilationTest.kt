package viewforge.packages.compose.codegen

import androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import viewforge.project.ProjectCodec
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
    private val fixtures = listOf("Demo", "RowBox", "Modifiers", "ModifierOrder")

    @Test
    fun `generated golden output compiles against Compose`() {
        val sources = fixtures.map { name ->
            val text = requireNotNull(javaClass.getResourceAsStream("/golden/$name.vforge")).bufferedReader().readText()
            val generated = ComposeCodeGenerator().generate(ProjectCodec.decode(text)).single()
            SourceFile.kotlin(generated.path, generated.content)
        }

        val logs = ByteArrayOutputStream()
        val result = KotlinCompilation().apply {
            this.sources = sources
            inheritClassPath = true
            compilerPluginRegistrars = listOf(ComposePluginRegistrar())
            messageOutputStream = PrintStream(logs)
            verbose = false
        }.compile()

        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "generated Compose code did not compile:\n$logs",
        )
    }
}

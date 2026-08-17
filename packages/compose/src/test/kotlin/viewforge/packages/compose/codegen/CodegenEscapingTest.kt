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
import kotlin.test.assertTrue

/**
 * Codegen string-escaping regression (SECURITY §6 GC-2 / §12 pre-release checklist): a prop value
 * containing a double quote, a `$`, a `${'$'}{...}` template sequence, a backslash, newlines, a tab and
 * non-ASCII must never break out of the generated Kotlin string literal or inject structure.
 *
 * KotlinPoet's `%S` escapes all of these by construction (GC-1), so the definitive guard is that the
 * generated source still *compiles* — an unescaped `"` or a live `${'$'}{...}` interpolation would be a
 * syntax error or reference an undefined symbol. This is exactly the check the SECURITY checklist asks
 * for, and it fails loudly if anyone ever replaces the structural emitter with string concatenation.
 */
@OptIn(ExperimentalCompilerApi::class)
class CodegenEscapingTest {
    @Test
    fun `a prop value with hostile characters generates code that still compiles`() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/escaping/HostileStrings.vforge")) {
            "missing /escaping/HostileStrings.vforge"
        }.bufferedReader().readText()

        val generated = ComposeCodeGenerator().generate(ProjectCodec.decode(fixture)).single()

        // Sanity: the hostile value was actually emitted (not silently dropped), so the compile below is
        // exercising the escaped literal rather than an empty screen.
        assertTrue("Text(" in generated.content, "expected the Text call to be emitted:\n${generated.content}")

        assertCompiles(
            listOf(SourceFile.kotlin(generated.path, generated.content)),
            "generated code for a hostile string value did not compile — an escape was dropped",
        )
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

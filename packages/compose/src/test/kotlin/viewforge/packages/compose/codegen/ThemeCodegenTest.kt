package viewforge.packages.compose.codegen

import viewforge.project.ProjectCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Theme codegen golden (H4, M8): the `AppTheme` wrapper generated from a project theme, asserted
 * byte-for-byte, plus the custom-typography inline `TextStyle` a screen emits when it references a
 * non-Material token. [CompilationTest] proves both compile.
 */
class ThemeCodegenTest {
    private fun resource(path: String): String =
        requireNotNull(javaClass.getResourceAsStream(path)) { "missing test resource $path" }
            .bufferedReader().readText()

    private val project = ProjectCodec.decode(resource("/golden/ThemeWrapper.vforge"))

    @Test
    fun themeWrapper() {
        val generated = ComposeCodeGenerator().generateTheme(project)!!
        dump("ThemeWrapper.Theme.kt", generated)
        assertEquals(resource("/golden/ThemeWrapper.Theme.kt").trimEnd('\n'), generated.trimEnd('\n'))
    }

    @Test
    fun screenWithCustomTypography() {
        val generated = ComposeCodeGenerator().generate(project).single().content
        dump("ThemeWrapper.kt", generated)
        assertEquals(resource("/golden/ThemeWrapper.kt").trimEnd('\n'), generated.trimEnd('\n'))
    }

    @Test
    fun emptyThemeEmitsNothing() {
        val bare = project.copy(theme = viewforge.model.Theme())
        assertNull(ComposeCodeGenerator().generateTheme(bare))
    }

    /** Writes the actual output to the scratchpad so a golden can be captured/inspected on mismatch. */
    private fun dump(name: String, content: String) {
        runCatching {
            val dir = System.getenv("VFORGE_GOLDEN_DUMP") ?: return
            File(dir).mkdirs()
            File(dir, name).writeText(content)
        }
    }
}

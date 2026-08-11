package viewforge.packages.compose.codegen

import viewforge.project.ProjectCodec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The codegen golden suite (G1, DATA_MODEL §11): every `.vforge` fixture under `resources/golden/`
 * must generate byte-for-byte the sibling `.kt` — IR in, expected Kotlin out. Adding a fixture pair
 * is the entire cost of a new golden case; this test discovers them, so there is no per-fixture code.
 *
 * The fixtures deliberately cover every supported component and modifier (Phase-1 exit #6, CLAUDE.md
 * rule 5), including a modifier-order permutation (`ModifierOrder`) — a suite that only checked which
 * modifiers are present would pass on wrong output (TECHNICAL_NOTES §1). [CompilationTest] then proves
 * the same output compiles.
 */
class GoldenCodegenTest {
    private fun resource(path: String): String =
        requireNotNull(javaClass.getResourceAsStream(path)) { "missing test resource $path" }
            .bufferedReader().readText()

    private fun assertGolden(name: String) {
        val project = ProjectCodec.decode(resource("/golden/$name.vforge"))
        val generated = ComposeCodeGenerator().generate(project).single().content
        // Normalize only the trailing newline so the check is about content, not editor line-ending.
        assertEquals(resource("/golden/$name.kt").trimEnd('\n'), generated.trimEnd('\n'))
    }

    @Test fun demo() = assertGolden("Demo")

    @Test fun rowBox() = assertGolden("RowBox")

    @Test fun modifiers() = assertGolden("Modifiers")

    @Test fun modifierOrder() = assertGolden("ModifierOrder")

    @Test fun lazy() = assertGolden("Lazy")

    @Test fun image() = assertGolden("Image")

    // The M9 "something real" screen: nested Column/Row/Box, Text, Buttons, Images, a scrollable list.
    @Test fun gallery() = assertGolden("Gallery")
}

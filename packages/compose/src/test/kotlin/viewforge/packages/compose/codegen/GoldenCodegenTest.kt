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

    // Card + Surface containers and a HorizontalDivider (Dp arg) — the layout/content half of the
    // post-Phase-1 catalog expansion (issue #16).
    @Test fun containers() = assertGolden("Containers")

    // Checkbox + Switch: Bool props and a raw onCheckedChange lambda — the input half (issue #16).
    @Test fun toggles() = assertGolden("Toggles")

    // OutlinedButton + TextButton: the button variants share Button's onClick + content shape (issue #16).
    @Test fun buttons() = assertGolden("Buttons")

    // Slider (Float prop + raw onValueChange) and the progress indicators (issue #16).
    @Test fun indicators() = assertGolden("Indicators")

    // Icon: a curated Material-icon enum emitted as `Icons.Filled.<name>` (extension-property import; issue #16).
    @Test fun icons() = assertGolden("Icons")

    // TextField + OutlinedTextField: String value + a raw onValueChange (issue #16).
    @Test fun textFields() = assertGolden("TextFields")

    // TopAppBar (named title slot + @OptIn) and BottomAppBar (RowScope content) (issue #16).
    @Test fun appBars() = assertGolden("AppBars")

    // Scaffold: topBar/bottomBar named slots + a content lambda with its PaddingValues (issue #16).
    @Test fun scaffold() = assertGolden("Scaffold")

    // The M9 "something real" screen: nested Column/Row/Box, Text, Buttons, Images, a scrollable list.
    @Test fun gallery() = assertGolden("Gallery")
}

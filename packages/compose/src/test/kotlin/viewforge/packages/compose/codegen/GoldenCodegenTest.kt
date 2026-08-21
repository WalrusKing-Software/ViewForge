package viewforge.packages.compose.codegen

import viewforge.project.ProjectCodec
import viewforge.spi.GeneratedFile
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

    /** Asserts the generated file at [path] matches the sibling golden [resourceName].kt. */
    private fun assertGeneratedFile(files: List<GeneratedFile>, path: String, resourceName: String) {
        val content = files.first { it.path == path }.content
        assertEquals(resource("/golden/$resourceName.kt").trimEnd('\n'), content.trimEnd('\n'))
    }

    @Test fun demo() = assertGolden("Demo")

    @Test fun rowBox() = assertGolden("RowBox")

    @Test fun modifiers() = assertGolden("Modifiers")

    @Test fun modifierOrder() = assertGolden("ModifierOrder")

    // weight (#158): a RowScope/ColumnScope-only modifier — emitted for Row/Column direct children (order
    // preserved with padding), and dropped for a child of a Box where the scope isn't present.
    @Test fun weight() = assertGolden("Weight")

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

    // Text styling props: fontSize (sp), fontWeight, textAlign, maxLines, overflow (issue #17).
    @Test fun textStyling() = assertGolden("TextStyling")

    // Button `enabled` across the three button variants (Bool prop; issue #17).
    @Test fun buttonStates() = assertGolden("ButtonStates")

    // Image `alignment` (Alignment enum) + `alpha` (Float), each independently omittable (issue #17).
    @Test fun imageAdjust() = assertGolden("ImageAdjust")

    // Text sp spacing props: letterSpacing + lineHeight, completing the sp trio with fontSize (issue #17).
    @Test fun textSpacing() = assertGolden("TextSpacing")

    // Text emphasis enums: fontStyle + textDecoration, completing Text's prop set (issue #17).
    @Test fun textEmphasis() = assertGolden("TextEmphasis")

    // Button styling via ButtonDefaults: themeable container/content colors, elevation, contentPadding (issue #17).
    @Test fun buttonStyling() = assertGolden("ButtonStyling")

    // Button shape: a literal corner radius (RoundedCornerShape) and a shapes.* Material token (issue #17).
    @Test fun buttonShape() = assertGolden("ButtonShape")

    // The M9 "something real" screen: nested Column/Row/Box, Text, Buttons, Images, a scrollable list.
    @Test fun gallery() = assertGolden("Gallery")

    // Read-only screen state (ADR-034, #21): a scalar-bound Text/Slider, a seeded stub + generated record
    // `data class`, and a `vforge.repeat` lowered to `members.forEach { item -> … }` with item-scoped bindings.
    @Test fun stateBinding() = assertGolden("StateBinding")

    // #298: a numeric (Int/Float) state field bound to a String prop (Text) is coerced with `.toString()`,
    // so a live number can be shown as text — a read-only Float `val` and a written Int `var` both display.
    @Test fun intText() = assertGolden("IntText")

    // A `vforge.repeat` in LazyColumn layout (ADR-034 slice 2, #251): lowers to
    // `LazyColumn { items(members) { item -> … } }` instead of the default inline `forEach`, with the
    // same seeded stub + record `data class` and item-scoped member-access bindings.
    @Test fun repeatLazyColumn() = assertGolden("RepeatLazyColumn")

    // A populated dropdown (ADR-034 slice 2, #253): a `vforge.dropdown` bound to a list-of-record state field
    // lowers to a `Box { OutlinedTextField(...) ; DropdownMenu { options.forEach { item -> DropdownMenuItem } } }`,
    // reading the seeded stub + generated `data class`, with the label field selecting the shown record field.
    @Test fun populatedDropdown() = assertGolden("PopulatedDropdown")

    // Nested lists (ADR-034 Amendment, #255): a list-of-record whose record has a nested list-of-record field.
    // Emits recursive `data class`es (Department has `teams: List<Team>`), a nested seeded stub, and a repeat
    // over `item.teams` inside the outer repeat — `departments.forEach { item -> … item.teams.forEach { item -> … } }`.
    @Test fun nestedList() = assertGolden("NestedList")

    // Interactive state & events (ADR-035, #277): a writable field emits `var count by remember { mutableStateOf(0) }`
    // (vs a read-only `val heading`), and a Button/TextButton `onClick` handler lowers its closed `Action` list to a
    // structural lambda — `Adjust`→`count += 1`, `Toggle`→`expanded = !expanded`, `SetState`→`count = 0`.
    @Test fun interactive() = assertGolden("Interactive")

    // Screen-to-screen navigation (ADR-039, #214): a Button `onClick` whose handler is a `Navigate` action
    // lowers to `onNavigate("scr_details")`, and the navigating screen gains an injected
    // `onNavigate: (String) -> Unit = {}` parameter (before `modifier`). A non-navigating screen is unchanged.
    @Test fun navigation() = assertGolden("Navigation")

    // Responsive per-breakpoint overrides (ADR-037, #222): a Text with `fontSize`/`textAlign` overridden at
    // the `medium`/`expanded` breakpoints lowers to a `BoxWithConstraints { }` that hoists each overridden prop
    // into a `val name = if (maxWidth >= 840.dp) … else if (maxWidth >= 600.dp) … else <base>` selected by width,
    // largest-first. The override-free sibling stays a plain `Text` — no wrapper leaks onto a non-responsive node.
    @Test fun responsive() = assertGolden("Responsive")

    // A user component + an instance that references it (D7): the screen emits a `PrimaryButton(...)`
    // call and the component emits its own composable file. One .vforge, two generated files — the
    // reference model (ADR-024), so this is a multi-file golden rather than a `.single()` case.
    @Test
    fun reusableComponent() {
        val project = ProjectCodec.decode(resource("/golden/ReusableComponent.vforge"))
        val files = ComposeCodeGenerator().generate(project)
        assertGeneratedFile(files, "HomeScreen.kt", "ReusableComponent")
        assertGeneratedFile(files, "PrimaryButton.kt", "ReusableComponent.PrimaryButton")
    }

    // Component-level navigation (#324): the NavCard component's own tree navigates, so NavCard.kt gains its own
    // `onNavigate: (String) -> Unit = {}` param and lowers Navigate onto it; the Hub screen navigates transitively
    // and forwards `onNavigate = onNavigate` to the instance call. One .vforge, two files asserted apart.
    @Test
    fun componentNavigation() {
        val project = ProjectCodec.decode(resource("/golden/ComponentNavigation.vforge"))
        val files = ComposeCodeGenerator().generate(project)
        assertGeneratedFile(files, "Hub.kt", "ComponentNavigation")
        assertGeneratedFile(files, "NavCard.kt", "ComponentNavigation.NavCard")
    }

    // Component-local state (ADR-034 Amendment, #269): a component carries BOTH a parameter and its own
    // state, so ProfileCard.kt emits `title: String` as a fn arg *and* seeds `heading`/`badges` locals with a
    // `data class Badge`, its repeat lowered to `badges.forEach { item -> … }` — params and StateBindings
    // coexisting. The screen emits the instance call `ProfileCard(title = "Team")`.
    @Test
    fun componentState() {
        val project = ProjectCodec.decode(resource("/golden/ComponentState.vforge"))
        val files = ComposeCodeGenerator().generate(project)
        assertGeneratedFile(files, "Directory.kt", "ComponentState")
        assertGeneratedFile(files, "ProfileCard.kt", "ComponentState.ProfileCard")
    }

    // A component with parameters (parameters slice 2, ADR-028): the component emits typed fn params
    // (String + a defaulted Boolean) with ParamRef bodies (`text = label`, `enabled = enabled`), and the
    // screen emits calls passing argument values — including one instance that omits the defaulted arg.
    @Test
    fun parameterizedComponent() {
        val project = ProjectCodec.decode(resource("/golden/ParameterizedComponent.vforge"))
        val files = ComposeCodeGenerator().generate(project)
        assertGeneratedFile(files, "HomeScreen.kt", "ParameterizedComponent")
        assertGeneratedFile(files, "PrimaryButton.kt", "ParameterizedComponent.PrimaryButton")
    }

    // Transitive component references (export completeness, #213): a screen instances InfoCard, which
    // *itself* instances PrimaryButton. All three generated files must be emitted from the one document —
    // codegen emits every `Project.components` entry, so a component used only through another component
    // still ships — and the InfoCard file must contain the nested `PrimaryButton(...)` call. Guards against
    // a future used-component pruning that could drop a transitively-referenced component from the export.
    @Test
    fun nestedComponent() {
        val project = ProjectCodec.decode(resource("/golden/NestedComponent.vforge"))
        val files = ComposeCodeGenerator().generate(project)
        assertGeneratedFile(files, "HomeScreen.kt", "NestedComponent")
        assertGeneratedFile(files, "InfoCard.kt", "NestedComponent.InfoCard")
        assertGeneratedFile(files, "PrimaryButton.kt", "NestedComponent.PrimaryButton")
    }
}

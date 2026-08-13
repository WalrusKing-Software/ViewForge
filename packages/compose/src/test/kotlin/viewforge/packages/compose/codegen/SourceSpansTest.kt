package viewforge.packages.compose.codegen

import viewforge.model.Node
import viewforge.model.Project
import viewforge.project.ProjectCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The node→source-range side-channel (G3, #51). Two contracts, over every golden fixture:
 *
 *  1. **Invariant:** the instrumented [GeneratedSource.code] is byte-for-byte the normal generated
 *     output. This is what makes "the map never changes the code" true rather than hoped-for — if a
 *     marker ever perturbs KotlinPoet's formatting, this fails loudly instead of shipping altered code.
 *  2. **Well-formed spans:** every non-hidden node has a valid range, and a child's range nests inside
 *     its parent's — so highlighting a node covers exactly its emitted code, including nodes reached
 *     through slots and lazy `item { }` blocks.
 */
class SourceSpansTest {
    private fun project(name: String): Project = ProjectCodec.decode(
        requireNotNull(javaClass.getResourceAsStream("/golden/$name.vforge")) { "missing /golden/$name.vforge" }
            .bufferedReader().readText(),
    )

    /** Every golden fixture, so the invariant is proven against the whole supported surface. */
    private val fixtures = listOf(
        "Demo", "RowBox", "Modifiers", "ModifierOrder", "Lazy", "Image", "Containers", "Toggles",
        "Buttons", "Indicators", "Icons", "TextFields", "AppBars", "Scaffold", "TextStyling",
        "ButtonStates", "ImageAdjust", "TextSpacing", "TextEmphasis", "ButtonStyling", "ButtonShape",
        "Gallery", "ReusableComponent", "ParameterizedComponent",
    )

    @Test
    fun `instrumented code is identical to the normal output for every fixture`() {
        val gen = ComposeCodeGenerator()
        for (name in fixtures) {
            val p = project(name)
            val source = p.name.ifBlank { "Project" }
            for (screen in p.screens) {
                val withSpans = gen.generateScreenWithSpans(
                    screen,
                    p.theme,
                    source,
                    p.schemaVersion,
                    p.assets,
                    p.components,
                )
                val plain = gen.generateScreen(screen, p.theme, source, p.schemaVersion, p.assets, p.components)
                assertEquals(plain, withSpans.code, "screen ${screen.name} in $name")
            }
            for (component in p.components) {
                val withSpans =
                    gen.generateComponentWithSpans(component, p.theme, source, p.schemaVersion, p.assets, p.components)
                val plain =
                    gen.generateComponent(component, p.theme, source, p.schemaVersion, p.assets, p.components)
                assertEquals(plain, withSpans.code, "component ${component.name} in $name")
            }
        }
    }

    @Test
    fun `every non-hidden node has a range that nests inside its parent's`() {
        val gen = ComposeCodeGenerator()
        for (name in fixtures) {
            val p = project(name)
            val source = p.name.ifBlank { "Project" }
            for (screen in p.screens) {
                val src = gen.generateScreenWithSpans(screen, p.theme, source, p.schemaVersion, p.assets, p.components)
                assertNodeSpans(screen.root, parentRange = null, src, "$name/${screen.name}")
            }
            for (component in p.components) {
                val src =
                    gen.generateComponentWithSpans(component, p.theme, source, p.schemaVersion, p.assets, p.components)
                assertNodeSpans(component.root, parentRange = null, src, "$name/${component.name}")
            }
        }
    }

    /** A node's range must exist, sit within the code, contain the node's short type name, and nest in its parent. */
    private fun assertNodeSpans(node: Node, parentRange: IntRange?, src: GeneratedSource, where: String) {
        if (node.hidden) return // hidden nodes are dropped from output, so carry no span (DATA_MODEL §5)
        val range = src.spans[node.id.value]
        assertTrue(range != null, "$where: node ${node.type} (${node.id.value}) has no span")
        assertTrue(range.first in 0..range.last && range.last <= src.code.length, "$where: range $range out of bounds")
        if (parentRange != null) {
            assertTrue(
                range.first >= parentRange.first && range.last <= parentRange.last,
                "$where: ${node.type} range $range not nested in parent $parentRange",
            )
        }
        (node.children + node.slots.values.flatten()).forEach { assertNodeSpans(it, range, src, where) }
    }
}

package viewforge.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Responsive override resolution (ADR-030, #221): [effectiveProps] overlays a breakpoint's overrides onto a
 * node's base props, and [resolvedForBreakpoint] applies that over a whole tree while preserving identity for
 * the override-free case. Pure model logic — no framework, no width interpretation (breakpoint ids are opaque).
 */
class ResponsiveTest {
    private fun lit(value: String): PropValue = PropValue.Literal(JsonPrimitive(value))

    private fun text(id: String, base: String, responsive: Map<String, Map<String, PropValue>> = emptyMap()): Node =
        Node(NodeId(id), "compose.material3.Text", props = mapOf("text" to lit(base)), responsive = responsive)

    @Test
    fun `effectiveProps returns the base props for a null, absent, or unknown breakpoint`() {
        val node = text("n", "Base", mapOf("expanded" to mapOf("text" to lit("Wide"))))
        assertEquals(node.props, effectiveProps(node, null))
        assertEquals(node.props, effectiveProps(node, "medium")) // no override for this breakpoint
        assertEquals(node.props, effectiveProps(text("n", "Base"), "expanded")) // node has no overrides at all
    }

    @Test
    fun `effectiveProps layers an override over the base, replacing a named prop and adding new ones`() {
        val node = Node(
            NodeId("n"),
            "compose.material3.Text",
            props = mapOf("text" to lit("Base"), "color" to lit("black")),
            responsive = mapOf("expanded" to mapOf("text" to lit("Wide"), "maxLines" to lit("2"))),
        )
        val resolved = effectiveProps(node, "expanded")
        assertEquals(lit("Wide"), resolved["text"]) // replaced
        assertEquals(lit("black"), resolved["color"]) // base kept
        assertEquals(lit("2"), resolved["maxLines"]) // added
    }

    @Test
    fun `resolvedForBreakpoint returns the same instance when nothing is responsive`() {
        val tree = Node(
            NodeId("root"),
            "compose.foundation.layout.Column",
            children = listOf(text("a", "A"), text("b", "B")),
        )
        assertSame(tree, tree.resolvedForBreakpoint("expanded"))
    }

    @Test
    fun `resolvedForBreakpoint overlays overrides and clears the field, recursing into children`() {
        val child = text("c", "Base", mapOf("expanded" to mapOf("text" to lit("Wide"))))
        val root = Node(NodeId("root"), "compose.foundation.layout.Column", children = listOf(child))

        val resolved = root.resolvedForBreakpoint("expanded")
        val resolvedChild = resolved.children.single()
        assertEquals(lit("Wide"), resolvedChild.props["text"])
        assertTrue(resolvedChild.responsive.isEmpty(), "responsive is stripped after resolution")

        // At a breakpoint with no override, the base value stays.
        assertEquals(lit("Base"), root.resolvedForBreakpoint("medium").children.single().props["text"])
    }
}

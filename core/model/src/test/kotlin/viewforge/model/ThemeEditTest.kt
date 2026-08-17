package viewforge.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThemeEditTest {
    private fun themeRef(token: String): PropValue = PropValue.ThemeRef(token)

    private fun literal(value: String): PropValue = PropValue.Literal(JsonPrimitive(value))

    // root (Column)
    // ├─ a (Text, color -> colors.primary, style -> typography.titleLarge)
    // └─ b (Box, modifier background color -> colors.primary)
    //     └─ slot "content": [ leaf (Text, color -> colors.secondary) ]
    private val leaf = Node(
        id = NodeId("leaf"),
        type = "compose.material3.Text",
        props = mapOf("color" to themeRef("colors.secondary")),
    )
    private val a = Node(
        id = NodeId("a"),
        type = "compose.material3.Text",
        props = mapOf("color" to themeRef("colors.primary"), "style" to themeRef("typography.titleLarge")),
    )
    private val b = Node(
        id = NodeId("b"),
        type = "compose.foundation.layout.Box",
        modifiers = listOf(
            ModifierEntry(id = "m1", type = "compose.background", args = mapOf("color" to themeRef("colors.primary"))),
        ),
        slots = mapOf("content" to listOf(leaf)),
    )
    private val root = Node(
        id = NodeId("root"),
        type = "compose.foundation.layout.Column",
        children = listOf(a, b),
    )

    private val theme = Theme(
        colors = mapOf(
            "primary" to ColorPair("#6750A4", "#D0BCFF"),
            "secondary" to ColorPair("#625B71", "#CCC2DC"),
        ),
    )

    // --- mapThemeRefs -----------------------------------------------------------------------------

    @Test
    fun `mapThemeRefs rewrites matching tokens in props, slots, and modifier args`() {
        val renamed = root.mapThemeRefs { if (it == "colors.primary") "colors.brand" else it }

        assertEquals("colors.brand", (renamed.children[0].props["color"] as PropValue.ThemeRef).token)
        // a non-matching ref on the same node is untouched
        assertEquals("typography.titleLarge", (renamed.children[0].props["style"] as PropValue.ThemeRef).token)
        val box = renamed.children[1]
        assertEquals("colors.brand", (box.modifiers[0].args["color"] as PropValue.ThemeRef).token)
        // the slotted leaf referenced a different token, so it is unchanged
        assertEquals("colors.secondary", (box.slots.getValue("content")[0].props["color"] as PropValue.ThemeRef).token)
    }

    @Test
    fun `mapThemeRefs leaves non-ThemeRef values alone`() {
        val node = Node(
            id = NodeId("n"),
            type = "compose.material3.Text",
            props = mapOf("text" to literal("hello"), "color" to themeRef("colors.primary")),
        )
        val out = node.mapThemeRefs { "colors.x" }
        assertEquals(literal("hello"), out.props["text"])
        assertEquals("colors.x", (out.props["color"] as PropValue.ThemeRef).token)
    }

    @Test
    fun `mapThemeRefs returns the same instance when nothing matches`() {
        assertSame(root, root.mapThemeRefs { it })
        assertSame(root, root.mapThemeRefs { token -> if (token == "colors.absent") "colors.x" else token })
    }

    // --- renameToken ------------------------------------------------------------------------------

    @Test
    fun `renameToken renames the key and preserves order`() {
        val out = theme.renameToken(ThemeCategory.COLORS, "primary", "brand")!!
        assertEquals(listOf("brand", "secondary"), out.colors.keys.toList())
        assertEquals(ColorPair("#6750A4", "#D0BCFF"), out.colors["brand"])
    }

    @Test
    fun `renameToken is null when source absent, target exists, or unchanged`() {
        assertNull(theme.renameToken(ThemeCategory.COLORS, "missing", "brand"))
        assertNull(theme.renameToken(ThemeCategory.COLORS, "primary", "secondary"))
        assertNull(theme.renameToken(ThemeCategory.COLORS, "primary", "primary"))
    }

    @Test
    fun `canRenameToken mirrors renameToken validity`() {
        assertTrue(theme.canRenameToken(ThemeCategory.COLORS, "primary", "brand"))
        assertFalse(theme.canRenameToken(ThemeCategory.COLORS, "primary", "secondary"))
        assertFalse(theme.canRenameToken(ThemeCategory.COLORS, "primary", "primary"))
        assertFalse(theme.canRenameToken(ThemeCategory.COLORS, "primary", ""))
    }
}

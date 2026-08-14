package viewforge.editor.state

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ChildAddress
import viewforge.model.ColorPair
import viewforge.model.FrameworkRef
import viewforge.model.ModifierArg
import viewforge.model.ModifierDefinition
import viewforge.model.ModifierEntry
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.PropType
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.model.findById
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Non-UI behaviour of [EditorState]: how user intents become commands, how selection is maintained
 * across edits and undo, and how drop validation gates moves. A [FakeCatalog] stands in for the
 * Compose package so these run without a composition or the framework.
 */
class EditorStateTest {
    /** Column/Row/Box are containers; Button has a `content` slot; Text is a leaf. */
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(
            PaletteEntry("compose.foundation.layout.Column", "Column", "Layout"),
            PaletteEntry("compose.material3.Text", "Text", "Content"),
            PaletteEntry("compose.material3.Button", "Button", "Input"),
        )

        override fun newNode(type: String): Node = when (type) {
            "compose.material3.Button" ->
                Node(
                    NodeId.random(),
                    type,
                    slots = mapOf(
                        "content" to listOf(Node(NodeId.random(), "compose.material3.Text")),
                    ),
                )
            else -> Node(NodeId.random(), type)
        }

        override fun acceptsChildren(type: String): Boolean = type.startsWith("compose.foundation.layout.")

        override fun slotsOf(type: String): List<String> = if (type ==
            "compose.material3.Button"
        ) {
            listOf("content")
        } else {
            emptyList()
        }

        override fun propsFor(type: String): List<PropDefinition> = if (type == "compose.material3.Text") {
            listOf(
                PropDefinition("text", PropType.String, default = PropValue.Literal(JsonPrimitive(""))),
                PropDefinition("color", PropType.Color, themeable = true),
            )
        } else {
            emptyList()
        }

        override val modifierCatalog: List<ModifierDefinition> = listOf(
            ModifierDefinition("compose.fillMaxSize", "Fill Max Size"),
            ModifierDefinition(
                "compose.padding",
                "Padding",
                args = listOf(ModifierArg("all", PropType.Dp, default = PropValue.Literal(JsonPrimitive(16)))),
            ),
        )

        override fun modifierDef(type: String): ModifierDefinition? = modifierCatalog.firstOrNull { it.type == type }
    }

    private val root = Node(
        id = NodeId("root"),
        type = "compose.foundation.layout.Column",
        children = listOf(
            Node(NodeId("a"), "compose.material3.Text"),
            Node(
                NodeId("b"),
                "compose.material3.Button",
                slots = mapOf("content" to listOf(Node(NodeId("leaf"), "compose.material3.Text"))),
            ),
        ),
    )

    private fun state(): EditorState = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(Screen("s1", "Home", root)),
        ),
        FakeCatalog(),
    )

    @Test
    fun `addFromPalette appends into a selected container and selects the new node`() {
        val s = state()
        s.select(NodeId("root"))
        s.addFromPalette("compose.material3.Text")
        val children = s.activeScreen!!.root.children
        assertEquals(3, children.size)
        assertEquals(children.last().id, s.selectedId)
    }

    @Test
    fun `addFromPalette on a leaf selection inserts a sibling after it`() {
        val s = state()
        s.select(NodeId("a")) // leaf Text at index 0
        s.addFromPalette("compose.material3.Text")
        assertEquals(NodeId("a"), s.activeScreen!!.root.children[0].id)
        assertEquals(s.selectedId, s.activeScreen!!.root.children[1].id) // new node landed right after "a"
    }

    @Test
    fun `dropPaletteDrag inserts the dragged type at the canvas-resolved address and selects it`() {
        val s = state()
        s.beginPaletteDrag("compose.material3.Text")
        s.updatePaletteDrag(10f, 20f)
        assertEquals("compose.material3.Text", s.paletteDragType)
        // The canvas resolves a drop between the two existing children (index 1) and publishes it.
        s.resolvePaletteDrop(ChildAddress(NodeId("root"), null, 1))
        s.dropPaletteDrag()

        val children = s.activeScreen!!.root.children
        assertEquals(3, children.size)
        assertEquals("compose.material3.Text", children[1].type) // landed at the resolved index
        assertEquals(children[1].id, s.selectedId)
        assertNull(s.paletteDragType) // the drag is cleared after commit
    }

    @Test
    fun `dropPaletteDrag off a legal target is a no-op but still clears the drag`() {
        val s = state()
        s.beginPaletteDrag("compose.material3.Text")
        s.updatePaletteDrag(10f, 20f)
        s.resolvePaletteDrop(null) // pointer wasn't over an accepting container
        s.dropPaletteDrag()

        assertEquals(2, s.activeScreen!!.root.children.size) // document unchanged
        assertNull(s.paletteDragType)
    }

    @Test
    fun `cancelPaletteDrag abandons the drag with no change`() {
        val s = state()
        s.beginPaletteDrag("compose.material3.Text")
        s.resolvePaletteDrop(ChildAddress(NodeId("root"), null, 0))
        s.cancelPaletteDrag()

        assertEquals(2, s.activeScreen!!.root.children.size)
        assertNull(s.paletteDragType)
    }

    @Test
    fun `deleteSelected removes the node and selects its parent`() {
        val s = state()
        s.select(NodeId("a"))
        s.deleteSelected()
        assertNull(s.activeScreen!!.root.findById(NodeId("a")))
        assertEquals(NodeId("root"), s.selectedId)
    }

    @Test
    fun `deleteSelected refuses to remove the root`() {
        val s = state()
        s.select(NodeId("root"))
        s.deleteSelected()
        assertEquals(NodeId("root"), s.activeScreen!!.root.id)
    }

    @Test
    fun `duplicateSelected clones with fresh ids next to the original`() {
        val s = state()
        s.select(NodeId("b"))
        s.duplicateSelected()
        val children = s.activeScreen!!.root.children
        assertEquals(3, children.size)
        val clone = children[2]
        assertTrue(clone.id != NodeId("b"))
        assertEquals("compose.material3.Button", clone.type)
        // Slot child was cloned too, with its own fresh id.
        val cloneSlotChild = clone.slots.getValue("content")[0]
        assertTrue(cloneSlotChild.id != NodeId("leaf"))
        assertEquals(clone.id, s.selectedId)
    }

    @Test
    fun `undo reverses an edit and restores selection target existence`() {
        val s = state()
        s.select(NodeId("a"))
        s.deleteSelected()
        assertNull(s.activeScreen!!.root.findById(NodeId("a")))
        s.undo()
        assertTrue(s.activeScreen!!.root.findById(NodeId("a")) != null)
    }

    @Test
    fun `copy and paste inserts a fresh clone at the selection`() {
        val s = state()
        s.select(NodeId("b"))
        s.copySelected()
        s.select(NodeId("root"))
        assertTrue(s.canPaste)
        s.paste()
        assertEquals(3, s.activeScreen!!.root.children.size)
        assertTrue(s.selectedId != NodeId("b"))
    }

    @Test
    fun `cut copies then deletes`() {
        val s = state()
        s.select(NodeId("a"))
        s.cut()
        assertNull(s.activeScreen!!.root.findById(NodeId("a")))
        assertTrue(s.canPaste)
    }

    @Test
    fun `locked node cannot be selected and locking clears the selection`() {
        val s = state()
        s.select(NodeId("a"))
        s.toggleLocked(NodeId("a"))
        assertNull(s.selectedId) // locking the selected node cleared it
        s.select(NodeId("a"))
        assertNull(s.selectedId) // and it can no longer be selected
    }

    @Test
    fun `canDrop rejects dropping into own descendant and into a non-container`() {
        val s = state()
        // Into own subtree: dragging root onto b (b is inside root) is illegal.
        assertFalse(s.canDrop(NodeId("root"), ChildAddress(NodeId("b"), "content", 0)))
        // Into a non-container default region: Text "a" accepts no children.
        assertFalse(s.canDrop(NodeId("b"), ChildAddress(NodeId("a"), null, 0)))
        // Legal: move "a" into b's content slot.
        assertTrue(s.canDrop(NodeId("a"), ChildAddress(NodeId("b"), "content", 0)))
    }

    @Test
    fun `moveNode ignores an illegal drop`() {
        val s = state()
        s.moveNode(NodeId("root"), ChildAddress(NodeId("b"), "content", 0))
        // Root unchanged: still holds a and b.
        assertEquals(listOf(NodeId("a"), NodeId("b")), s.activeScreen!!.root.children.map { it.id })
    }

    // --- M5: property & modifier editing ----------------------------------------------------------

    @Test
    fun `setProp then resetProp round-trips through the default`() {
        val s = state()
        val v = PropValue.Literal(JsonPrimitive("Hello"))
        s.setProp(NodeId("a"), "text", v)
        assertEquals(v, s.activeScreen!!.root.findById(NodeId("a"))!!.props["text"])
        // The Text 'text' def defaults to an empty literal; reset restores that.
        val def = s.catalog.propsFor("compose.material3.Text").first { it.name == "text" }
        s.resetProp(NodeId("a"), def)
        assertEquals(def.default, s.activeScreen!!.root.findById(NodeId("a"))!!.props["text"])
    }

    @Test
    fun `addModifier appends with schema-default args and undo removes it`() {
        val s = state()
        s.addModifier(NodeId("a"), "compose.padding")
        val mods = s.activeScreen!!.root.findById(NodeId("a"))!!.modifiers
        assertEquals(1, mods.size)
        assertEquals("compose.padding", mods[0].type)
        assertEquals(PropValue.Literal(JsonPrimitive(16)), mods[0].args["all"]) // default applied
        s.undo()
        assertTrue(s.activeScreen!!.root.findById(NodeId("a"))!!.modifiers.isEmpty())
    }

    @Test
    fun `toggleModifier flips enabled without deleting`() {
        val s = state()
        s.addModifier(NodeId("a"), "compose.fillMaxSize")
        val id = s.activeScreen!!.root.findById(NodeId("a"))!!.modifiers[0].id
        s.toggleModifier(NodeId("a"), id)
        assertFalse(s.activeScreen!!.root.findById(NodeId("a"))!!.modifiers[0].enabled)
    }

    @Test
    fun `moveModifier reorders the chain`() {
        val node = Node(
            NodeId("m"),
            "compose.foundation.layout.Box",
            modifiers = listOf(
                ModifierEntry("m1", "compose.padding"),
                ModifierEntry("m2", "compose.fillMaxSize"),
            ),
        )
        val s = EditorState(
            Project(
                id = "p",
                name = "P",
                framework = FrameworkRef("compose-multiplatform", "1.0.0"),
                screens = listOf(Screen("s1", "H", node)),
            ),
            FakeCatalog(),
        )
        s.moveModifier(NodeId("m"), from = 0, to = 1)
        assertEquals(listOf("m2", "m1"), s.activeScreen!!.root.modifiers.map { it.id })
    }

    @Test
    fun `setModifierArg edits a single arg and coalesced edits are one undo`() {
        val s = state()
        s.addModifier(NodeId("a"), "compose.padding")
        val id = s.activeScreen!!.root.findById(NodeId("a"))!!.modifiers[0].id
        // Two consecutive edits to the same arg coalesce.
        s.setModifierArg(NodeId("a"), id, "all", PropValue.Literal(JsonPrimitive(20)))
        s.setModifierArg(NodeId("a"), id, "all", PropValue.Literal(JsonPrimitive(24)))
        assertEquals(
            PropValue.Literal(JsonPrimitive(24)),
            s.activeScreen!!.root.findById(NodeId("a"))!!.modifiers[0].args["all"],
        )
        // One undo reverts both arg edits back to the add-time default (16).
        s.undo()
        assertEquals(
            PropValue.Literal(JsonPrimitive(16)),
            s.activeScreen!!.root.findById(NodeId("a"))!!.modifiers[0].args["all"],
        )
    }

    // --- theme editing (M8) -----------------------------------------------------------------------

    @Test
    fun `toggleCanvasDark flips the preview mode without touching the document`() {
        val s = state()
        val before = s.document
        assertFalse(s.canvasDark)
        s.toggleCanvasDark()
        assertTrue(s.canvasDark)
        assertEquals(before, s.document) // view state only
    }

    @Test
    fun `toggleChromeDark flips the editor chrome independently of the canvas preview`() {
        val s = state()
        val before = s.document
        // Chrome defaults to dark (the previously hardcoded scheme); the canvas preview defaults to light.
        assertTrue(s.chromeDark)
        assertFalse(s.canvasDark)

        s.toggleChromeDark()
        assertFalse(s.chromeDark)
        assertFalse(s.canvasDark) // the two themes are independent — toggling one never moves the other

        s.toggleCanvasDark()
        assertFalse(s.chromeDark)
        assertTrue(s.canvasDark)
        assertEquals(before, s.document) // view state only
    }

    @Test
    fun `addColor then setColor edits the theme and coalesces the scrub`() {
        val s = state()
        s.addColor("primary")
        s.setColor("primary", ColorPair("#111111", "#222222"))
        s.setColor("primary", ColorPair("#333333", "#444444"))
        assertEquals(ColorPair("#333333", "#444444"), s.theme.colors["primary"])
        // The two setColor edits coalesce (same token key); one undo reverts to the add-time default.
        s.undo()
        assertEquals(ColorPair("#000000", "#FFFFFF"), s.theme.colors["primary"])
        // A second undo removes the token (the add was its own discrete step).
        s.undo()
        assertNull(s.theme.colors["primary"])
    }

    @Test
    fun `renameColor propagates to references in one undoable step`() {
        val s = state()
        s.addColor("primary")
        s.setProp(NodeId("a"), "color", PropValue.ThemeRef("colors.primary"))
        s.renameColor("primary", "brand")
        assertTrue("brand" in s.theme.colors)
        assertEquals(
            PropValue.ThemeRef("colors.brand"),
            s.activeScreen!!.root.findById(NodeId("a"))!!.props["color"],
        )
        s.undo() // one undo reverts both the map key and the reference
        assertEquals(
            PropValue.ThemeRef("colors.primary"),
            s.activeScreen!!.root.findById(NodeId("a"))!!.props["color"],
        )
    }
}

package viewforge.editor.state

import viewforge.model.ChildAddress
import viewforge.model.FrameworkRef
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
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
}

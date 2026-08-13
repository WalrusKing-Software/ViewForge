package viewforge.editor.state

import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import viewforge.model.findById
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Batch operations over a multi-selection (C10 slice 3): delete, duplicate, and the multi-node clipboard
 * (copy/cut/paste) act on every selected node as a single undoable step, and leave the resulting set
 * selected. Nested selections (an ancestor and its descendant both selected) are handled once, via the
 * ancestor. Single-selection behavior is unchanged — that is covered by [EditorStateTest].
 */
class BatchOperationsTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.foundation.layout.Column", "Column", "Layout"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = type.startsWith("compose.foundation.layout.")

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private val a = Node(NodeId("a"), "compose.material3.Text")
    private val b = Node(NodeId("b"), "compose.material3.Text")
    private val c = Node(NodeId("c"), "compose.material3.Text")
    private val bx = Node(NodeId("bx"), "compose.material3.Text")
    private val box = Node(NodeId("box"), "compose.foundation.layout.Box", children = listOf(bx))
    private val root = Node(
        NodeId("root"),
        "compose.foundation.layout.Column",
        children = listOf(a, b, c, box),
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
    fun `deleteSelected removes every selected node as one undoable step`() {
        val s = state()
        s.toggleSelection(NodeId("a"))
        s.toggleSelection(NodeId("b"))
        s.deleteSelected()

        val now = s.activeEditRoot!!
        assertNull(now.findById(NodeId("a")))
        assertNull(now.findById(NodeId("b")))
        assertNotNull(now.findById(NodeId("c")))

        // A single undo restores both, proving it was one history entry.
        assertTrue(s.canUndo)
        s.undo()
        assertFalse(s.canUndo)
        assertNotNull(s.activeEditRoot!!.findById(NodeId("a")))
        assertNotNull(s.activeEditRoot!!.findById(NodeId("b")))
    }

    @Test
    fun `deleteSelected leaves the primary's parent selected`() {
        val s = state()
        s.toggleSelection(NodeId("a"))
        s.toggleSelection(NodeId("b")) // primary = b
        s.deleteSelected()
        assertEquals(listOf(NodeId("root")), s.selectedIds) // b's parent
    }

    @Test
    fun `duplicateSelected clones each selected node next to it and selects the clones`() {
        val s = state()
        s.toggleSelection(NodeId("a"))
        s.toggleSelection(NodeId("b"))
        s.duplicateSelected()

        val kids = s.activeEditRoot!!.children
        assertEquals(6, kids.size)
        assertEquals(NodeId("a"), kids[0].id)
        assertEquals(NodeId("b"), kids[2].id)
        assertEquals(NodeId("c"), kids[4].id)
        assertEquals(NodeId("box"), kids[5].id)
        // The clones sit at 1 and 3, carry fresh ids, and are the new selection.
        val cloneIds = listOf(kids[1].id, kids[3].id)
        assertTrue(cloneIds.none { it in listOf(NodeId("a"), NodeId("b"), NodeId("c"), NodeId("box")) })
        assertEquals(cloneIds.toSet(), s.selectedIds.toSet())

        s.undo()
        assertFalse(s.canUndo) // one step
        assertEquals(4, s.activeEditRoot!!.children.size)
    }

    @Test
    fun `copy then paste inserts a clone of each copied node and selects them`() {
        val s = state()
        s.toggleSelection(NodeId("a"))
        s.toggleSelection(NodeId("b"))
        s.copySelected()
        s.select(NodeId("c")) // paste targets the selection: right after c

        s.paste()
        val kids = s.activeEditRoot!!.children
        assertEquals(6, kids.size)
        assertEquals(NodeId("c"), kids[2].id)
        val clones = listOf(kids[3].id, kids[4].id)
        assertTrue(clones.none { it in listOf(NodeId("a"), NodeId("b"), NodeId("c"), NodeId("box")) })
        assertEquals(clones.toSet(), s.selectedIds.toSet())
    }

    @Test
    fun `a nested selection is handled once via the ancestor`() {
        val s = state()
        s.toggleSelection(NodeId("box"))
        s.toggleSelection(NodeId("bx")) // bx lives inside box
        s.deleteSelected()

        assertNull(s.activeEditRoot!!.findById(NodeId("box")))
        assertNull(s.activeEditRoot!!.findById(NodeId("bx")))
        // One step: undo restores the whole box subtree.
        s.undo()
        assertFalse(s.canUndo)
        assertNotNull(s.activeEditRoot!!.findById(NodeId("box")))
        assertNotNull(s.activeEditRoot!!.findById(NodeId("bx")))
    }

    @Test
    fun `cut copies the selection then deletes it in one step`() {
        val s = state()
        s.toggleSelection(NodeId("a"))
        s.toggleSelection(NodeId("b"))
        s.cut()

        assertNull(s.activeEditRoot!!.findById(NodeId("a")))
        assertNull(s.activeEditRoot!!.findById(NodeId("b")))
        assertTrue(s.canPaste) // the cut nodes are on the clipboard

        s.select(NodeId("c"))
        s.paste()
        assertEquals(4, s.activeEditRoot!!.children.size) // c, box + two pasted clones
        assertEquals(2, s.selectedIds.size)
    }
}

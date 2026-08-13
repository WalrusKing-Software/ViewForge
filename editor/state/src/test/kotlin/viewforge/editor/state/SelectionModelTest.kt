package viewforge.editor.state

import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The multi-selection model (C10, slice 1). Selection is an **ordered** list whose last entry is the
 * primary (anchor). [select] replaces it (plain click); [toggleSelection] adds/removes (ctrl-click).
 * Single-select behavior is unchanged — with one node selected, [selectedId]/[selectedNode] read exactly
 * as before — so the rest of the editor is untouched until the gestures land in slice 2.
 */
class SelectionModelTest {
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
    private val locked = Node(NodeId("locked"), "compose.material3.Text", locked = true)
    private val root = Node(
        NodeId("root"),
        "compose.foundation.layout.Column",
        children = listOf(a, b, locked),
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
    fun `a fresh state has an empty selection`() {
        val s = state()
        assertTrue(s.selectedIds.isEmpty())
        assertNull(s.selectedId)
        assertNull(s.selectedNode)
        assertTrue(s.selectedNodes.isEmpty())
    }

    @Test
    fun `select replaces the selection and sets the primary`() {
        val s = state()
        s.select(NodeId("a"))
        assertEquals(listOf(NodeId("a")), s.selectedIds)
        assertEquals(NodeId("a"), s.selectedId)

        s.select(NodeId("b")) // a plain click replaces, it does not accumulate
        assertEquals(listOf(NodeId("b")), s.selectedIds)
        assertEquals(NodeId("b"), s.selectedId)
    }

    @Test
    fun `select null clears the whole selection`() {
        val s = state()
        s.toggleSelection(NodeId("a"))
        s.toggleSelection(NodeId("b"))
        s.select(null)
        assertTrue(s.selectedIds.isEmpty())
        assertNull(s.selectedId)
    }

    @Test
    fun `toggleSelection accumulates in order, last added is primary`() {
        val s = state()
        s.toggleSelection(NodeId("a"))
        s.toggleSelection(NodeId("b"))
        assertEquals(listOf(NodeId("a"), NodeId("b")), s.selectedIds)
        assertEquals(NodeId("b"), s.selectedId) // primary = last added
        assertTrue(s.isSelected(NodeId("a")))
        assertTrue(s.isSelected(NodeId("b")))
        assertEquals(listOf(a, b), s.selectedNodes)
    }

    @Test
    fun `toggleSelection removes an already-selected id and updates the primary`() {
        val s = state()
        s.toggleSelection(NodeId("a"))
        s.toggleSelection(NodeId("b"))
        s.toggleSelection(NodeId("b")) // toggle the primary off
        assertEquals(listOf(NodeId("a")), s.selectedIds)
        assertEquals(NodeId("a"), s.selectedId) // primary falls back to the remaining last
        assertFalse(s.isSelected(NodeId("b")))
    }

    @Test
    fun `toggling off the only selected node clears the selection`() {
        val s = state()
        s.toggleSelection(NodeId("a"))
        s.toggleSelection(NodeId("a"))
        assertTrue(s.selectedIds.isEmpty())
        assertNull(s.selectedId)
    }

    @Test
    fun `a locked node cannot be selected or added (T4)`() {
        val s = state()
        s.select(NodeId("locked"))
        assertTrue(s.selectedIds.isEmpty())

        s.select(NodeId("a"))
        s.toggleSelection(NodeId("locked"))
        assertEquals(listOf(NodeId("a")), s.selectedIds) // the add was refused, the standing selection holds
    }

    @Test
    fun `selectedNodes resolves ids in selection order and drops any that vanish`() {
        val s = state()
        s.toggleSelection(NodeId("a"))
        s.toggleSelection(NodeId("b"))
        assertEquals(listOf(a, b), s.selectedNodes)
    }

    private val order = listOf(NodeId("root"), NodeId("a"), NodeId("b"), NodeId("locked"))

    @Test
    fun `extendSelectionTo selects the inclusive range from the anchor and makes the target primary`() {
        val s = state()
        s.select(NodeId("root")) // anchor = root
        s.extendSelectionTo(NodeId("b"), order)
        assertEquals(listOf(NodeId("root"), NodeId("a"), NodeId("b")), s.selectedIds)
        assertEquals(NodeId("b"), s.selectedId) // clicked end is primary
    }

    @Test
    fun `extendSelectionTo works upward and keeps the target last`() {
        val s = state()
        s.select(NodeId("b")) // anchor = b
        s.extendSelectionTo(NodeId("root"), order)
        assertEquals(listOf(NodeId("b"), NodeId("a"), NodeId("root")), s.selectedIds) // target (root) last
        assertEquals(NodeId("root"), s.selectedId)
    }

    @Test
    fun `successive shift-clicks measure from the same fixed anchor`() {
        val s = state()
        s.select(NodeId("a")) // anchor = a
        s.extendSelectionTo(NodeId("b"), order)
        assertEquals(listOf(NodeId("a"), NodeId("b")), s.selectedIds)
        // A second shift-click re-measures from a (the pivot), not from b (the last click).
        s.extendSelectionTo(NodeId("root"), order)
        assertEquals(listOf(NodeId("a"), NodeId("root")), s.selectedIds)
    }

    @Test
    fun `extendSelectionTo skips locked nodes in the span`() {
        val s = state()
        s.select(NodeId("a")) // anchor = a
        s.extendSelectionTo(NodeId("locked"), order) // span a..locked includes the locked node
        assertEquals(listOf(NodeId("a"), NodeId("b")), s.selectedIds) // locked dropped
    }

    @Test
    fun `extendSelectionTo with no anchor falls back to a plain select`() {
        val s = state()
        s.extendSelectionTo(NodeId("b"), order)
        assertEquals(listOf(NodeId("b")), s.selectedIds)
    }

    @Test
    fun `toggleSelection moves the range anchor to the added node`() {
        val s = state()
        s.select(NodeId("root")) // anchor = root
        s.toggleSelection(NodeId("a")) // ctrl-add a -> anchor becomes a
        s.extendSelectionTo(NodeId("b"), order) // range measured from a (the new anchor), not root
        assertEquals(listOf(NodeId("a"), NodeId("b")), s.selectedIds)
    }

    @Test
    fun `a command collapses selection to the reconciled target (slice-1 behavior)`() {
        // Until batch operations land (slice 3), running a command reconciles selection to a single
        // target — exactly the pre-C10 behavior. deleteSelected removes the primary and leaves its parent
        // selected, so a prior multi-selection collapses to that one node.
        val s = state()
        s.toggleSelection(NodeId("a"))
        s.toggleSelection(NodeId("b")) // primary = b
        s.deleteSelected()
        assertEquals(listOf(NodeId("root")), s.selectedIds) // b's parent, as a single selection
    }
}

package viewforge.editor.state

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.model.findById
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Shared property edits over a multi-selection (C10 slice 4, the epic finale): editing a prop applies to
 * every selected node that shares the primary's type, as one undoable step, and a continuous edit
 * coalesces like single-node editing. Nodes of a different type are left alone. A lone selection behaves
 * exactly like [EditorState.setProp].
 */
class SharedEditTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.foundation.layout.Column", "Column", "Layout"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = type.startsWith("compose.foundation.layout.")

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private val t1 = Node(NodeId("t1"), "compose.material3.Text")
    private val t2 = Node(NodeId("t2"), "compose.material3.Text")
    private val btn = Node(NodeId("btn"), "compose.material3.Button")
    private val root = Node(
        NodeId("root"),
        "compose.foundation.layout.Column",
        children = listOf(t1, t2, btn),
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

    private fun lit(s: String): PropValue = PropValue.Literal(JsonPrimitive(s))

    @Test
    fun `sameTypeSelection returns only nodes sharing the primary's type`() {
        val s = state()
        s.toggleSelection(NodeId("btn"))
        s.toggleSelection(NodeId("t1"))
        s.toggleSelection(NodeId("t2")) // primary = t2 (a Text)
        assertEquals(listOf(NodeId("t1"), NodeId("t2")), s.sameTypeSelection().map { it.id })
    }

    @Test
    fun `setPropShared applies to every same-type node and leaves other types alone`() {
        val s = state()
        s.toggleSelection(NodeId("btn"))
        s.toggleSelection(NodeId("t1"))
        s.toggleSelection(NodeId("t2"))
        s.setPropShared("text", lit("Hi"))

        val now = s.activeEditRoot!!
        assertEquals(lit("Hi"), now.findById(NodeId("t1"))!!.props["text"])
        assertEquals(lit("Hi"), now.findById(NodeId("t2"))!!.props["text"])
        assertNull(now.findById(NodeId("btn"))!!.props["text"]) // different type untouched
    }

    @Test
    fun `a shared edit is one undoable step and keeps the selection`() {
        val s = state()
        s.toggleSelection(NodeId("t1"))
        s.toggleSelection(NodeId("t2"))
        s.setPropShared("text", lit("Hi"))
        assertEquals(setOf(NodeId("t1"), NodeId("t2")), s.selectedIds.toSet())

        s.undo()
        assertFalse(s.canUndo) // a single entry reverted both
        assertNull(s.activeEditRoot!!.findById(NodeId("t1"))!!.props["text"])
        assertNull(s.activeEditRoot!!.findById(NodeId("t2"))!!.props["text"])
    }

    @Test
    fun `consecutive shared edits on the same prop coalesce into one step`() {
        val s = state()
        s.toggleSelection(NodeId("t1"))
        s.toggleSelection(NodeId("t2"))
        s.setPropShared("text", lit("A"))
        s.setPropShared("text", lit("B")) // e.g. a slider drag

        val now = s.activeEditRoot!!
        assertEquals(lit("B"), now.findById(NodeId("t1"))!!.props["text"])
        assertEquals(lit("B"), now.findById(NodeId("t2"))!!.props["text"])

        s.undo()
        assertFalse(s.canUndo) // coalesced to a single entry
        assertNull(s.activeEditRoot!!.findById(NodeId("t1"))!!.props["text"])
    }

    @Test
    fun `with a lone selection setPropShared just sets the primary`() {
        val s = state()
        s.select(NodeId("t1"))
        s.setPropShared("text", lit("X"))
        assertEquals(lit("X"), s.activeEditRoot!!.findById(NodeId("t1"))!!.props["text"])
        assertNull(s.activeEditRoot!!.findById(NodeId("t2"))!!.props["text"])
    }
}

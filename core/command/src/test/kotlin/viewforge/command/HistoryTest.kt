package viewforge.command

import kotlinx.serialization.json.JsonPrimitive
import viewforge.command.Fixtures.rootOf
import viewforge.model.ChildAddress
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropValue
import viewforge.model.findById
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryTest {
    private val doc0 = Fixtures.project()

    private fun add(name: String, index: Int) = AddNode(
        Fixtures.SCREEN,
        ChildAddress(NodeId("root"), null, index),
        Node(NodeId(name), "compose.material3.Text"),
    )

    private fun lit(s: String): PropValue = PropValue.Literal(JsonPrimitive(s))

    private fun asString(value: PropValue?): String? = (value as? PropValue.Literal)?.value?.content

    @Test
    fun `undo then redo restores each state`() {
        val history = History()
        val doc1 = history.execute(add("x", 2), doc0)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        val back = history.undo(doc1)
        assertEquals(doc0, back)
        assertTrue(history.canRedo)

        val forward = history.redo(back)
        assertEquals(doc1, forward)
    }

    @Test
    fun `a new execute clears the redo stack`() {
        val history = History()
        val doc1 = history.execute(add("x", 2), doc0)
        val back = history.undo(doc1)
        assertTrue(history.canRedo)

        history.execute(add("y", 2), back)
        assertFalse(history.canRedo) // diverged: the old redo is gone
    }

    @Test
    fun `undo and redo are no-ops on empty stacks`() {
        val history = History()
        assertEquals(doc0, history.undo(doc0))
        assertEquals(doc0, history.redo(doc0))
    }

    @Test
    fun `history is capped, dropping the oldest entry`() {
        val history = History(limit = 2)
        var doc = doc0
        doc = history.execute(RenameNode(Fixtures.SCREEN, NodeId("a"), "one"), doc)
        doc = history.execute(RenameNode(Fixtures.SCREEN, NodeId("a"), "two"), doc)
        doc = history.execute(RenameNode(Fixtures.SCREEN, NodeId("a"), "three"), doc)

        // Only the last two renames are undoable; unwinding both cannot reach the original "a" name.
        doc = history.undo(doc)
        doc = history.undo(doc)
        assertFalse(history.canUndo)
        assertEquals("one", doc.rootOf().findById(NodeId("a"))!!.name) // "two"→"one", not back to null
    }

    @Test
    fun `consecutive same-key edits coalesce into one undo step`() {
        val history = History()
        val id = NodeId("a")
        var doc = doc0
        // Simulate a scrub: three edits to the same prop in a row.
        doc = history.execute(SetProp(Fixtures.SCREEN, id, "text", lit("a")), doc)
        doc = history.execute(SetProp(Fixtures.SCREEN, id, "text", lit("ab")), doc)
        doc = history.execute(SetProp(Fixtures.SCREEN, id, "text", lit("abc")), doc)
        assertEquals("abc", doc.rootOf().findById(id)!!.props["text"].let(::asString))

        // A single undo reverts the whole run back to the original (prop absent), not one keystroke.
        doc = history.undo(doc)
        assertNull(doc.rootOf().findById(id)!!.props["text"])
        assertFalse(history.canUndo)

        // Redo re-applies the latest state.
        doc = history.redo(doc)
        assertEquals("abc", doc.rootOf().findById(id)!!.props["text"].let(::asString))
    }

    @Test
    fun `edits to different props do not coalesce`() {
        val history = History()
        val id = NodeId("a")
        var doc = doc0
        doc = history.execute(SetProp(Fixtures.SCREEN, id, "text", lit("x")), doc)
        doc = history.execute(SetProp(Fixtures.SCREEN, id, "color", lit("#FFF")), doc)
        // Two distinct entries: one undo removes only the color.
        doc = history.undo(doc)
        assertNull(doc.rootOf().findById(id)!!.props["color"])
        assertEquals("x", doc.rootOf().findById(id)!!.props["text"].let(::asString))
        assertTrue(history.canUndo)
    }

    @Test
    fun `labels track the pending undo and redo`() {
        val history = History()
        val doc1 = history.execute(RenameNode(Fixtures.SCREEN, NodeId("a"), "n"), doc0)
        assertEquals("Rename", history.undoLabel)
        history.undo(doc1)
        assertEquals("Rename", history.redoLabel)
    }
}

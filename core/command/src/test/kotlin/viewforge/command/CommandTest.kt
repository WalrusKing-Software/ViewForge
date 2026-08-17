package viewforge.command

import viewforge.command.Fixtures.rootOf
import viewforge.model.ChildAddress
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.findById
import viewforge.model.locate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Each command's [Command.apply] does the edit, and `invert(before).apply(after)` restores the exact
 * pre-image — the invariant History relies on. Asserting on the whole [viewforge.model.Project] keeps
 * these honest about structural details (positions, slots, flags).
 */
class CommandTest {
    private val doc = Fixtures.project()

    private fun assertRoundTrips(command: Command) {
        val inverse = command.invert(doc)
        val after = command.apply(doc)
        assertEquals(doc, inverse.apply(after), "invert(before).apply(after) must equal before")
    }

    @Test
    fun `AddNode inserts and round-trips`() {
        val node = Node(id = NodeId("new"), type = "compose.material3.Text")
        val cmd = AddNode(Fixtures.SCREEN, ChildAddress(NodeId("root"), null, 1), node)
        val after = cmd.apply(doc)
        assertEquals(
            listOf(NodeId("a"), NodeId("new"), NodeId("b")),
            after.rootOf().children.map { it.id },
        )
        assertRoundTrips(cmd)
    }

    @Test
    fun `RemoveNode deletes a slotted child and round-trips`() {
        val cmd = RemoveNode(Fixtures.SCREEN, NodeId("leaf"))
        val after = cmd.apply(doc)
        assertNull(after.rootOf().findById(NodeId("leaf")))
        assertRoundTrips(cmd)
    }

    @Test
    fun `RemoveNode on an absent id is a no-op with a no-op inverse`() {
        val cmd = RemoveNode(Fixtures.SCREEN, NodeId("ghost"))
        assertEquals(doc, cmd.apply(doc))
        assertRoundTrips(cmd)
    }

    @Test
    fun `MoveNode reparents into a slot and round-trips`() {
        // Move the top-level Text "a" into the Button's content slot, after the existing leaf.
        val target = ChildAddress(NodeId("b"), "content", 1)
        val cmd = MoveNode(Fixtures.SCREEN, NodeId("a"), target)
        val after = cmd.apply(doc)
        assertTrue(after.rootOf().children.none { it.id == NodeId("a") })
        assertEquals(
            listOf(NodeId("leaf"), NodeId("a")),
            after.rootOf().findById(NodeId("b"))!!.slots.getValue("content").map { it.id },
        )
        assertRoundTrips(cmd)
    }

    @Test
    fun `MoveNode reorders within the same region and round-trips`() {
        // Move "b" before "a": index 0 in the post-removal list.
        val cmd = MoveNode(Fixtures.SCREEN, NodeId("b"), ChildAddress(NodeId("root"), null, 0))
        val after = cmd.apply(doc)
        assertEquals(listOf(NodeId("b"), NodeId("a")), after.rootOf().children.map { it.id })
        assertRoundTrips(cmd)
    }

    @Test
    fun `RenameNode sets and clears the name and round-trips`() {
        val cmd = RenameNode(Fixtures.SCREEN, NodeId("a"), "Heading")
        val after = cmd.apply(doc)
        assertEquals("Heading", after.rootOf().findById(NodeId("a"))!!.name)
        assertRoundTrips(cmd)
        // Blank names normalize to null so the tree falls back to the type label.
        val blank = RenameNode(Fixtures.SCREEN, NodeId("a"), "   ").apply(doc)
        assertNull(blank.rootOf().findById(NodeId("a"))!!.name)
    }

    @Test
    fun `SetNodeFlags toggles only the given flags and round-trips`() {
        val cmd = SetNodeFlags(Fixtures.SCREEN, NodeId("b"), hidden = true)
        val after = cmd.apply(doc)
        val b = after.rootOf().findById(NodeId("b"))!!
        assertTrue(b.hidden)
        assertTrue(!b.locked) // untouched
        assertRoundTrips(cmd)
    }

    @Test
    fun `CompositeCommand applies in order and inverts in reverse`() {
        val add = AddNode(
            Fixtures.SCREEN,
            ChildAddress(NodeId("root"), null, 2),
            Node(id = NodeId("c"), type = "compose.material3.Text"),
        )
        val rename = RenameNode(Fixtures.SCREEN, NodeId("a"), "First")
        val composite = CompositeCommand(listOf(add, rename))
        val after = composite.apply(doc)
        assertEquals(3, after.rootOf().children.size)
        assertEquals("First", after.rootOf().findById(NodeId("a"))!!.name)
        assertRoundTrips(composite)
    }

    @Test
    fun `MoveNode invert targets the original located address`() {
        val cmd = MoveNode(Fixtures.SCREEN, NodeId("a"), ChildAddress(NodeId("b"), "content", 0))
        val inverse = cmd.invert(doc) as MoveNode
        assertEquals(doc.rootOf().locate(NodeId("a")), inverse.target)
    }
}

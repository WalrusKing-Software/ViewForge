package viewforge.command

import kotlinx.serialization.json.JsonPrimitive
import viewforge.command.Fixtures.componentRootOf
import viewforge.model.ChildAddress
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropValue
import viewforge.model.findById
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Root-agnostic node editing (edit-in-place slice 1, ADR-027): the same node commands that edit a
 * *screen* root must edit a *component* root when their `rootId` names a component. Each applies to the
 * component "c1" and inverts back to the exact prior tree, so component edits will undo/redo like screen
 * edits — without any editor wiring yet (that is slice 2).
 */
class ComponentEditCommandTest {
    private val cid = Fixtures.COMPONENT
    private val ca = NodeId("c-a") // componentText
    private val cb = NodeId("c-b") // componentButton

    @Test
    fun `AddNode targets a component root and inverts`() {
        val before = Fixtures.projectWithComponent()
        val fresh = Node(NodeId("c-new"), "compose.material3.Text")
        val cmd = AddNode(cid, ChildAddress(NodeId("c-root"), null, 2), fresh)

        val after = cmd.apply(before)
        assertEquals(listOf(ca, cb, NodeId("c-new")), after.componentRootOf().children.map { it.id })
        // Screens are untouched by a component edit.
        assertEquals(before.screens, after.screens)

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.components, restored.components)
    }

    @Test
    fun `RemoveNode targets a component root and inverts to the exact position`() {
        val before = Fixtures.projectWithComponent()
        val cmd = RemoveNode(cid, ca)

        val after = cmd.apply(before)
        assertNull(after.componentRootOf().findById(ca))

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.components, restored.components)
    }

    @Test
    fun `MoveNode reorders within a component root and inverts`() {
        val before = Fixtures.projectWithComponent()
        val cmd = MoveNode(cid, ca, ChildAddress(NodeId("c-root"), null, 1)) // c-a after c-b

        val after = cmd.apply(before)
        assertEquals(listOf(cb, ca), after.componentRootOf().children.map { it.id })

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.components, restored.components)
    }

    @Test
    fun `SetProp edits a node inside a component and inverts`() {
        val before = Fixtures.projectWithComponent()
        val cmd = SetProp(cid, ca, "text", PropValue.Literal(JsonPrimitive("Hi")))

        val after = cmd.apply(before)
        assertEquals(
            PropValue.Literal(JsonPrimitive("Hi")),
            after.componentRootOf().findById(ca)?.props?.get("text"),
        )

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.components, restored.components)
    }

    @Test
    fun `RenameNode and SetNodeFlags edit inside a component and invert`() {
        val before = Fixtures.projectWithComponent()

        val renamed = RenameNode(cid, ca, "Heading").apply(before)
        assertEquals("Heading", renamed.componentRootOf().findById(ca)?.name)
        assertEquals(before.components, RenameNode(cid, ca, "Heading").invert(before).apply(renamed).components)

        val hidden = SetNodeFlags(cid, cb, hidden = true).apply(before)
        assertTrue(hidden.componentRootOf().findById(cb)!!.hidden)
        assertEquals(before.components, SetNodeFlags(cid, cb, hidden = true).invert(before).apply(hidden).components)
    }

    @Test
    fun `a command targeting an unknown root id is a no-op`() {
        val before = Fixtures.projectWithComponent()
        assertEquals(before, RemoveNode("nope", ca).apply(before))
        assertTrue(RemoveNode("nope", ca).invert(before) is NoOp)
    }
}

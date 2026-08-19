package viewforge.command

import kotlinx.serialization.json.JsonPrimitive
import viewforge.command.Fixtures.rootOf
import viewforge.model.Action
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropValue
import viewforge.model.findById
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The event-handler command (ADR-035, #277). [SetHandler] must set a node's handler slot, invert back to the
 * exact prior list so undo round-trips, clear the slot on an empty list, and target either a screen or a
 * component root. Order is semantic and preserved.
 */
class HandlerCommandTest {
    private val onClick = "onClick"
    private fun handlersOf(project: Project, id: String) = project.rootOf().findById(NodeId(id))?.handlers

    @Test
    fun `SetHandler sets a slot's ordered actions and inverts by restoring the prior (empty) slot`() {
        val before = Fixtures.project()
        val actions = listOf(
            Action.Adjust("count", PropValue.Literal(JsonPrimitive(1))),
            Action.Toggle("open"),
        )
        val cmd = SetHandler(Fixtures.SCREEN, NodeId("b"), onClick, actions)
        val after = cmd.apply(before)
        assertEquals(actions, handlersOf(after, "b")?.get(onClick))

        val restored = cmd.invert(before).apply(after)
        // The button had no handlers to begin with, so the slot is gone again (omitted, not an empty map entry).
        assertNull(handlersOf(restored, "b")?.get(onClick))
        assertEquals(before, restored)
    }

    @Test
    fun `SetHandler with an empty list clears the slot`() {
        val seeded = SetHandler(
            Fixtures.SCREEN,
            NodeId("b"),
            onClick,
            listOf(Action.Toggle("open")),
        ).apply(Fixtures.project())
        val cleared = SetHandler(Fixtures.SCREEN, NodeId("b"), onClick, emptyList()).apply(seeded)
        assertNull(handlersOf(cleared, "b")?.get(onClick))
    }

    @Test
    fun `SetHandler inverts back to a prior non-empty list`() {
        val first = SetHandler(
            Fixtures.SCREEN,
            NodeId("b"),
            onClick,
            listOf(Action.Toggle("a")),
        ).apply(Fixtures.project())
        val second = SetHandler(Fixtures.SCREEN, NodeId("b"), onClick, listOf(Action.Toggle("b")))
        val after = second.apply(first)
        val restored = second.invert(first).apply(after)
        assertEquals(listOf(Action.Toggle("a")), handlersOf(restored, "b")?.get(onClick))
    }

    @Test
    fun `SetHandler on an absent node returns the project unchanged by identity`() {
        val before = Fixtures.project()
        val after = SetHandler(Fixtures.SCREEN, NodeId("nope"), onClick, listOf(Action.Toggle("x"))).apply(before)
        assertSame(before, after)
    }

    @Test
    fun `SetHandler targets a component root by id`() {
        val before = Fixtures.projectWithComponent()
        val actions = listOf(Action.Navigate("s1"))
        val after = SetHandler(Fixtures.COMPONENT, NodeId("c-b"), onClick, actions).apply(before)
        assertEquals(actions, after.components.first().root.findById(NodeId("c-b"))?.handlers?.get(onClick))
    }
}

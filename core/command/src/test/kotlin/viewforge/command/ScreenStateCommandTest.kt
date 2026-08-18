package viewforge.command

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.RecordField
import viewforge.model.SampleValue
import viewforge.model.ScalarType
import viewforge.model.Screen
import viewforge.model.StateField
import viewforge.model.StateType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The read-only screen-state commands (ADR-034, #21): declaring, editing, and removing a [StateField]
 * must each apply cleanly and invert back to the exact prior state, so undo/redo round-trips. Absent
 * targets collapse to a no-op inverse rather than corrupting history.
 */
class ScreenStateCommandTest {
    private val online = StateField(
        name = "isOnline",
        type = StateType.Scalar(ScalarType.BOOL),
        sample = SampleValue.Scalar(JsonPrimitive(true)),
    )
    private val users = StateField(
        name = "users",
        type = StateType.ListOfRecord(listOf(RecordField("name", ScalarType.STRING))),
        sample = SampleValue.Rows(listOf(mapOf("name" to JsonPrimitive("Ada")))),
    )

    private fun screen(id: String, name: String, state: List<StateField> = emptyList()) =
        Screen(id = id, name = name, root = Node(NodeId("$id-root"), "compose.foundation.layout.Column"), state = state)

    private fun doc(state: List<StateField> = emptyList()) = Fixtures.project().copy(
        screens = listOf(screen("s1", "Home", state), screen("s2", "Details")),
    )

    private fun stateOf(doc: viewforge.model.Project, id: String = "s1") = doc.screens.first { it.id == id }.state

    @Test
    fun `AddStateField appends the field and inverts by removing it`() {
        val before = doc()
        val cmd = AddStateField("s1", online)
        val after = cmd.apply(before)
        assertEquals(listOf(online), stateOf(after))
        // The other screen is untouched.
        assertEquals(emptyList(), stateOf(after, "s2"))

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.screens, restored.screens)
    }

    @Test
    fun `RemoveStateField removes by name and inverts by restoring at its old index`() {
        val before = doc(listOf(users, online)) // remove the first so restoring at index 0 is meaningful
        val cmd = RemoveStateField("s1", "users")
        val after = cmd.apply(before)
        assertEquals(listOf(online), stateOf(after))

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.screens, restored.screens)
    }

    @Test
    fun `RemoveStateField of an absent name inverts to a no-op`() {
        assertTrue(RemoveStateField("s1", "nope").invert(doc(listOf(online))) is NoOp)
    }

    @Test
    fun `InsertStateField clamps an out-of-range index to an append`() {
        val after = InsertStateField("s1", index = 99, field = online).apply(doc(listOf(users)))
        assertEquals(listOf(users, online), stateOf(after))
    }

    @Test
    fun `SetStateField replaces the entry in place and inverts to the prior one`() {
        val before = doc(listOf(users, online))
        val renamed = online.copy(name = "connected")
        val cmd = SetStateField("s1", index = 1, field = renamed)
        val after = cmd.apply(before)
        assertEquals(listOf(users, renamed), stateOf(after))

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.screens, restored.screens)
    }

    @Test
    fun `SetStateField coalesces per screen and index`() {
        val a = SetStateField("s1", 0, users)
        val b = SetStateField("s1", 0, users.copy(name = "people"))
        assertEquals(a.coalesceKey, b.coalesceKey)
        assertTrue(SetStateField("s1", 1, users).coalesceKey != a.coalesceKey)
    }

    @Test
    fun `SetStateField at an out-of-range index inverts to a no-op`() {
        assertTrue(SetStateField("s1", index = 5, field = online).invert(doc(listOf(online))) is NoOp)
    }
}

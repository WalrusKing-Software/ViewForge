package viewforge.editor.state

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.Action
import viewforge.model.PropValue
import viewforge.model.SampleValue
import viewforge.model.ScalarType
import viewforge.model.StateField
import viewforge.model.StateType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure action-editing helpers behind the inspector's data-driven action editor (ADR-035, #277): which kinds
 * a surface can offer, the default action each produces, and how re-targeting and value edits stay type-correct.
 * The "no free text" guarantee lives here, so the UI just wires these.
 */
class ActionEditingTest {
    private fun scalar(name: String, type: ScalarType, sample: Any) =
        StateField(name, StateType.Scalar(type), SampleValue.Scalar(prim(sample)))

    private fun prim(v: Any) = when (v) {
        is String -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> error("x")
    }

    private val fields = listOf(
        scalar("title", ScalarType.STRING, "Hi"),
        scalar("count", ScalarType.INT, 0),
        scalar("flag", ScalarType.BOOL, false),
        StateField(
            "rows",
            StateType.ListOfRecord(listOf(viewforge.model.RecordField("name", ScalarType.STRING))),
            viewforge.model.scalarRows(emptyList()),
        ),
    )

    @Test
    fun `availableActionKinds offers only kinds with a compatible target`() {
        assertEquals(
            listOf(ActionKind.SetState, ActionKind.Toggle, ActionKind.Adjust, ActionKind.Navigate),
            availableActionKinds(fields, hasScreens = true),
        )
        // No numeric/bool scalar, no screens → only Set (the String) is offered.
        val onlyString = listOf(scalar("title", ScalarType.STRING, "Hi"))
        assertEquals(listOf(ActionKind.SetState), availableActionKinds(onlyString, hasScreens = false))
    }

    @Test
    fun `defaultActionFor builds a first-compatible-target action with a typed default value`() {
        assertEquals(
            Action.SetState("title", PropValue.Literal(JsonPrimitive(""))),
            defaultActionFor(ActionKind.SetState, fields, screenIds = emptyList()),
        )
        assertEquals(Action.Toggle("flag"), defaultActionFor(ActionKind.Toggle, fields, emptyList()))
        assertEquals(
            Action.Adjust("count", PropValue.Literal(JsonPrimitive(1))),
            defaultActionFor(ActionKind.Adjust, fields, emptyList()),
        )
        assertEquals(Action.Navigate("s1"), defaultActionFor(ActionKind.Navigate, fields, listOf("s1", "s2")))
        assertNull(defaultActionFor(ActionKind.Navigate, fields, emptyList()))
    }

    @Test
    fun `retarget switches the target and resets the value to a type default`() {
        val set = Action.SetState("title", PropValue.Literal(JsonPrimitive("x")))
        // Re-target a String setter onto the Int field → value resets to the Int default 0.
        assertEquals(
            Action.SetState("count", PropValue.Literal(JsonPrimitive(0))),
            retarget(set, "count", fields),
        )
        // Navigate re-targets to a screen id.
        assertEquals(Action.Navigate("s2"), retarget(Action.Navigate("s1"), "s2", fields))
    }

    @Test
    fun `withScalarValue and scalarValueOf round-trip an editable value`() {
        val adjust = Action.Adjust("count", PropValue.Literal(JsonPrimitive(1)))
        val bumped = withScalarValue(adjust, JsonPrimitive(5))
        assertEquals(JsonPrimitive(5), scalarValueOf(bumped))
        // Toggle carries no value.
        assertNull(scalarValueOf(Action.Toggle("flag")))
        assertEquals(Action.Toggle("flag"), withScalarValue(Action.Toggle("flag"), JsonPrimitive(9)))
    }

    @Test
    fun `valueScalarType reports the target field type for editable value kinds`() {
        assertEquals(
            ScalarType.INT,
            valueScalarType(Action.Adjust("count", PropValue.Literal(JsonPrimitive(1))), fields),
        )
        assertEquals(
            ScalarType.STRING,
            valueScalarType(Action.SetState("title", PropValue.Literal(JsonPrimitive(""))), fields),
        )
        assertNull(valueScalarType(Action.Toggle("flag"), fields))
    }

    @Test
    fun `action kind maps every variant and marks the editable ones`() {
        assertEquals(ActionKind.Adjust, Action.Adjust("count", PropValue.Literal(JsonPrimitive(1))).kind)
        assertEquals(ActionKind.AppendRow, Action.AppendRow("rows", emptyMap()).kind)
        assertTrue(ActionKind.SetState.isEditable)
        assertTrue(!ActionKind.AppendRow.isEditable)
    }

    @Test
    fun `targetsFor lists the compatible field or screen names`() {
        assertEquals(listOf("count"), targetsFor(ActionKind.Adjust, fields, emptyList()))
        assertEquals(listOf("flag"), targetsFor(ActionKind.Toggle, fields, emptyList()))
        assertEquals(listOf("rows"), targetsFor(ActionKind.RemoveRow, fields, emptyList()))
        assertEquals(listOf("s1"), targetsFor(ActionKind.Navigate, fields, listOf("s1")))
    }
}

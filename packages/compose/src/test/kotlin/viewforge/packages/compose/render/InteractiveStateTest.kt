package viewforge.packages.compose.render

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.Action
import viewforge.model.PropValue
import viewforge.model.SampleValue
import viewforge.model.ScalarType
import viewforge.model.StateField
import viewforge.model.StateType
import viewforge.model.scalarRows
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The C13 run-mode reducer (ADR-035, #277): pure `when` over the closed [Action] set applied to the ephemeral
 * state store. No composition and no evaluation (PF-4) — mirrors [StateBindingTest]'s pure structural coverage.
 */
class InteractiveStateTest {
    private fun scalar(v: Any): SampleValue.Scalar = SampleValue.Scalar(
        when (v) {
            is Int -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Double -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        },
    )

    private fun intField(name: String, v: Int) = StateField(name, StateType.Scalar(ScalarType.INT), scalar(v))

    private fun boolField(name: String, v: Boolean) = StateField(name, StateType.Scalar(ScalarType.BOOL), scalar(v))

    @Test
    fun `initial state seeds each field from its sample`() {
        val state = initialInteractiveState(listOf(intField("count", 3), boolField("on", true)))
        assertEquals(scalar(3), state["count"])
        assertEquals(scalar(true), state["on"])
    }

    @Test
    fun `Adjust adds an integer delta to a numeric field`() {
        val state = mapOf<String, SampleValue>("count" to scalar(5))
        val next = applyAction(state, Action.Adjust("count", PropValue.Literal(JsonPrimitive(2))))
        assertEquals(scalar(7), next["count"])
    }

    @Test
    fun `Adjust adds a float delta when the field is a float`() {
        val state = mapOf<String, SampleValue>("ratio" to scalar(0.5))
        val next = applyAction(state, Action.Adjust("ratio", PropValue.Literal(JsonPrimitive(0.25))))
        assertEquals(JsonPrimitive(0.75), (next["ratio"] as SampleValue.Scalar).value)
    }

    @Test
    fun `Toggle flips a boolean field`() {
        val state = mapOf<String, SampleValue>("expanded" to scalar(false))
        assertEquals(scalar(true), applyAction(state, Action.Toggle("expanded"))["expanded"])
    }

    @Test
    fun `SetState with a literal replaces the value`() {
        val state = mapOf<String, SampleValue>("title" to scalar("old"))
        val next = applyAction(state, Action.SetState("title", PropValue.Literal(JsonPrimitive("new"))))
        assertEquals(scalar("new"), next["title"])
    }

    @Test
    fun `SetState with a binding reads another top-level field's current value`() {
        val state = mapOf<String, SampleValue>("a" to scalar(1), "b" to scalar(9))
        val next = applyAction(state, Action.SetState("a", PropValue.StateBinding("b")))
        assertEquals(scalar(9), next["a"])
    }

    @Test
    fun `AppendRow adds a row to a list field and RemoveRow drops one by index`() {
        val rows = scalarRows(listOf(mapOf("label" to JsonPrimitive("Ada"))))
        val state = mapOf<String, SampleValue>("todos" to rows)

        val appended = applyAction(
            state,
            Action.AppendRow("todos", mapOf("label" to PropValue.Literal(JsonPrimitive("Grace")))),
        )
        assertEquals(2, (appended["todos"] as SampleValue.Rows).rows.size)

        val removed = applyAction(appended, Action.RemoveRow("todos", PropValue.Literal(JsonPrimitive(0))))
        val remaining = (removed["todos"] as SampleValue.Rows).rows
        assertEquals(1, remaining.size)
        assertEquals(JsonPrimitive("Grace"), (remaining[0].getValue("label") as SampleValue.Scalar).value)
    }

    @Test
    fun `Navigate leaves the state store unchanged`() {
        val state = mapOf<String, SampleValue>("count" to scalar(1))
        assertEquals(state, applyAction(state, Action.Navigate("other")))
    }

    // Run-mode preview screen switching (#325): nextScreen is the pure host decision (which screen to show next).
    private val screenIds = setOf("home", "details", "settings")

    @Test
    fun `nextScreen switches to a Navigate target that is a known screen (#325)`() {
        assertEquals("details", nextScreen("home", listOf(Action.Navigate("details")), screenIds))
    }

    @Test
    fun `nextScreen ignores a Navigate to an unknown screen, staying put (PF-6, #325)`() {
        assertEquals("home", nextScreen("home", listOf(Action.Navigate("ghost")), screenIds))
    }

    @Test
    fun `nextScreen stays on the current screen when no action navigates (#325)`() {
        val actions = listOf(Action.Toggle("expanded"), Action.Adjust("count", PropValue.Literal(JsonPrimitive(1))))
        assertEquals("home", nextScreen("home", actions, screenIds))
        assertEquals("home", nextScreen("home", emptyList(), screenIds))
    }

    @Test
    fun `nextScreen takes the last Navigate when a handler has several, and fires alongside state actions (#325)`() {
        val actions = listOf(
            Action.SetState("count", PropValue.Literal(JsonPrimitive(0))),
            Action.Navigate("details"),
            Action.Navigate("settings"),
        )
        assertEquals("settings", nextScreen("home", actions, screenIds))
    }

    @Test
    fun `an unresolved target or value is a no-op (PF-6)`() {
        val state = mapOf<String, SampleValue>("count" to scalar(1))
        // Unknown target: nothing to toggle/adjust.
        assertEquals(state, applyAction(state, Action.Toggle("missing")))
        // Toggling a non-boolean field does nothing.
        assertEquals(state, applyAction(state, Action.Toggle("count")))
        // RemoveRow out of range leaves the list intact.
        val listState = mapOf<String, SampleValue>("xs" to scalarRows(listOf(mapOf("a" to JsonPrimitive(1)))))
        assertEquals(listState, applyAction(listState, Action.RemoveRow("xs", PropValue.Literal(JsonPrimitive(5)))))
    }

    @Test
    fun `applyActions folds a handler list in order (Adjust then Toggle)`() {
        val state = initialInteractiveState(listOf(intField("count", 0), boolField("expanded", false)))
        val next = applyActions(
            state,
            listOf(
                Action.Adjust("count", PropValue.Literal(JsonPrimitive(1))),
                Action.Toggle("expanded"),
            ),
        )
        assertEquals(scalar(1), next["count"])
        assertEquals(scalar(true), next["expanded"])
    }
}

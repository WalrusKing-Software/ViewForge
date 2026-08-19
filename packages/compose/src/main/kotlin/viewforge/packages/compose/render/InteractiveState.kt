package viewforge.packages.compose.render

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import viewforge.model.Action
import viewforge.model.PropValue
import viewforge.model.SampleValue
import viewforge.model.StateField

/**
 * The C13 run-mode (#120) interactive **state store and reducer** for ADR-035 event handlers. In interactive
 * preview the canvas backs each writable [StateField] with an ephemeral copy of its `sample`, and a widget's
 * event slot (e.g. a Button's `onClick`) applies its handler's [Action] list to that copy — nothing is persisted
 * to the IR. This is the whole run-mode behaviour, and it is a **pure `when` over the closed [Action] set**: no
 * parser, no evaluator (PF-4 stays literally true, exactly as binding resolution does). Kept Compose-free so it
 * is unit-testable without a composition; the [ComposeRenderer] holds it in `remember` and re-derives the tree.
 *
 * State is a flat `field name -> current [SampleValue]` map — the same typed-literal shape as declared samples —
 * so the live values feed straight back through [expandScreenState] to redraw bound props. A binding *value*
 * inside an action (e.g. `SetState("a", binding "b")`) reads another top-level field's current value; an `item.*`
 * path (a repeat row) is design-time sample data, not a writable store, so it does not resolve here. Any action
 * whose target/value doesn't resolve is a no-op (PF-6 discipline — never a crash, never dynamic dispatch).
 */

/** The event-slot key a Button fires (ADR-035). Promoted to catalog-declared slot metadata in the inspector slice. */
internal const val ON_CLICK: String = "onClick"

/** The initial run-mode state: each declared field seeded from its design-time [StateField.sample]. */
internal fun initialInteractiveState(state: List<StateField>): Map<String, SampleValue> =
    state.associate { it.name to it.sample }

/** Apply a handler's whole [actions] list in order, folding the state through each [applyAction]. */
internal fun applyActions(state: Map<String, SampleValue>, actions: List<Action>): Map<String, SampleValue> =
    actions.fold(state) { acc, action -> applyAction(acc, action) }

/** Apply one [action] to [state], returning the next state (or [state] unchanged if it can't resolve). */
internal fun applyAction(state: Map<String, SampleValue>, action: Action): Map<String, SampleValue> = when (action) {
    is Action.SetState ->
        resolveScalar(action.value, state)?.let { state + (action.target to SampleValue.Scalar(it)) } ?: state

    is Action.Toggle -> {
        val current = (state[action.target] as? SampleValue.Scalar)?.value?.booleanOrNull
        if (current != null) state + (action.target to SampleValue.Scalar(JsonPrimitive(!current))) else state
    }

    is Action.Adjust -> {
        val current = (state[action.target] as? SampleValue.Scalar)?.value
        adjusted(current, resolveScalar(action.by, state))
            ?.let { state + (action.target to SampleValue.Scalar(it)) } ?: state
    }

    is Action.AppendRow -> {
        val rows = (state[action.target] as? SampleValue.Rows)?.rows
        if (rows == null) {
            state
        } else {
            val row = action.row.mapValues { (_, v) ->
                SampleValue.Scalar(resolveScalar(v, state) ?: JsonPrimitive(""))
            }
            state + (action.target to SampleValue.Rows(rows + row))
        }
    }

    is Action.RemoveRow -> {
        val rows = (state[action.target] as? SampleValue.Rows)?.rows
        val index = resolveScalar(action.index, state)?.intOrNull
        if (rows != null && index != null && index in rows.indices) {
            state + (action.target to SampleValue.Rows(rows.filterIndexed { i, _ -> i != index }))
        } else {
            state
        }
    }

    // Navigation is a host concern (screen switching), not a change to this screen's state store.
    is Action.Navigate -> state
}

/**
 * A [PropValue] resolved to a scalar primitive against the current [state]: a [PropValue.Literal] is its own
 * value; a [PropValue.StateBinding] reads another **top-level** scalar field's current value (a single-segment
 * path — an `item.*` repeat path or a list field yields null). Any other kind yields null. No evaluation (PF-4).
 */
private fun resolveScalar(value: PropValue, state: Map<String, SampleValue>): JsonPrimitive? = when (value) {
    is PropValue.Literal -> value.value
    is PropValue.StateBinding -> (state[value.path] as? SampleValue.Scalar)?.value
    else -> null
}

/** [current] + [by] as Int when both parse as Int, else as Double when both parse as Double, else null. */
private fun adjusted(current: JsonPrimitive?, by: JsonPrimitive?): JsonPrimitive? {
    if (current == null || by == null) return null
    val ci = current.content.toIntOrNull()
    val bi = by.content.toIntOrNull()
    if (ci != null && bi != null) return JsonPrimitive(ci + bi)
    val cd = current.content.toDoubleOrNull()
    val bd = by.content.toDoubleOrNull()
    if (cd != null && bd != null) return JsonPrimitive(cd + bd)
    return null
}

package viewforge.editor.state

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.Action
import viewforge.model.PropValue
import viewforge.model.ScalarType
import viewforge.model.StateField
import viewforge.model.StateType
import viewforge.model.resolveWritableScalar

/**
 * Pure, Compose-free helpers behind the inspector's **data-driven action editor** (ADR-035, #277). An event
 * handler is an ordered [Action] list; the editor never takes free text — it offers a closed set of action
 * kinds, and for each a **writable target picked from the surface's declared state** and a typed literal (or a
 * later binding) value. These functions build and re-target actions so the UI stays thin and the "no free
 * expression" guarantee (PF-4) lives in one tested place, exactly as [bindablePaths] does for read bindings.
 *
 * This slice's editor offers the scalar/navigation kinds — [ActionKind.SetState] / [ActionKind.Toggle] /
 * [ActionKind.Adjust] / [ActionKind.Navigate] — the canonical counter/flag/navigation cases. `AppendRow` /
 * `RemoveRow` remain fully modelled, rendered, and generated; their multi-cell row editor is a follow-up.
 */
enum class ActionKind(val label: String) {
    SetState("Set"),
    Toggle("Toggle"),
    Adjust("Adjust by"),
    Navigate("Go to screen"),
    AppendRow("Append row"),
    RemoveRow("Remove row"),
}

/** The [ActionKind] of an existing [Action], so the editor can render any handler (even list actions it can't yet edit). */
val Action.kind: ActionKind
    get() = when (this) {
        is Action.SetState -> ActionKind.SetState
        is Action.Toggle -> ActionKind.Toggle
        is Action.Adjust -> ActionKind.Adjust
        is Action.Navigate -> ActionKind.Navigate
        is Action.AppendRow -> ActionKind.AppendRow
        is Action.RemoveRow -> ActionKind.RemoveRow
    }

/** True when the editor offers an inline editor for [kind] this slice (scalar/nav kinds; list kinds are display-only). */
val ActionKind.isEditable: Boolean
    get() = this == ActionKind.SetState ||
        this == ActionKind.Toggle ||
        this == ActionKind.Adjust ||
        this == ActionKind.Navigate

/** The declared scalar state fields — the assignable targets for `SetState`. */
fun scalarStateTargets(fields: List<StateField>): List<StateField> = fields.filter { it.type is StateType.Scalar }

/** The boolean state fields — the flippable targets for `Toggle`. */
fun boolStateTargets(fields: List<StateField>): List<StateField> =
    fields.filter { (it.type as? StateType.Scalar)?.scalar == ScalarType.BOOL }

/** The numeric (Int/Float) state fields — the nudgeable targets for `Adjust`. */
fun numericStateTargets(fields: List<StateField>): List<StateField> = fields.filter {
    val s = (it.type as? StateType.Scalar)?.scalar
    s == ScalarType.INT || s == ScalarType.FLOAT
}

/**
 * The action kinds the editor can offer given the surface's declared [fields] and whether [hasScreens]: a kind
 * appears only when a compatible target exists (a `Set` needs a scalar, `Toggle` a bool, `Adjust` a number,
 * `Navigate` another screen) — so the picker never lets you build an action with no valid target.
 */
fun availableActionKinds(fields: List<StateField>, hasScreens: Boolean): List<ActionKind> = buildList {
    if (scalarStateTargets(fields).isNotEmpty()) add(ActionKind.SetState)
    if (boolStateTargets(fields).isNotEmpty()) add(ActionKind.Toggle)
    if (numericStateTargets(fields).isNotEmpty()) add(ActionKind.Adjust)
    if (hasScreens) add(ActionKind.Navigate)
}

/** The valid targets for [kind] — the state fields (or screen ids) the target picker offers. */
fun targetsFor(kind: ActionKind, fields: List<StateField>, screenIds: List<String>): List<String> = when (kind) {
    ActionKind.SetState -> scalarStateTargets(fields).map { it.name }
    ActionKind.Toggle -> boolStateTargets(fields).map { it.name }
    ActionKind.Adjust -> numericStateTargets(fields).map { it.name }
    ActionKind.Navigate -> screenIds
    ActionKind.AppendRow, ActionKind.RemoveRow -> fields.filter { it.type is StateType.ListOfRecord }.map { it.name }
}

/**
 * A fresh action of [kind] with its first compatible target and a type-appropriate default value, or null when
 * no target is available. What the "+ action" button and a kind change produce; the row editor then refines it.
 */
fun defaultActionFor(kind: ActionKind, fields: List<StateField>, screenIds: List<String>): Action? = when (kind) {
    ActionKind.SetState -> scalarStateTargets(fields).firstOrNull()
        ?.let { Action.SetState(it.name, PropValue.Literal(defaultScalar(it.scalarOr(ScalarType.STRING)))) }
    ActionKind.Toggle -> boolStateTargets(fields).firstOrNull()?.let { Action.Toggle(it.name) }
    ActionKind.Adjust -> numericStateTargets(fields).firstOrNull()
        ?.let { Action.Adjust(it.name, PropValue.Literal(oneOf(it.scalarOr(ScalarType.INT)))) }
    ActionKind.Navigate -> screenIds.firstOrNull()?.let { Action.Navigate(it) }
    ActionKind.AppendRow, ActionKind.RemoveRow -> null
}

/**
 * The same [action] pointed at a new [target], resetting the value to a type default so it stays valid (a bool
 * target has no value; a `Navigate` target is a screen id). List actions keep their value as-is.
 */
fun retarget(action: Action, target: String, fields: List<StateField>): Action = when (action) {
    is Action.SetState -> Action.SetState(target, PropValue.Literal(defaultScalar(scalarOf(target, fields))))
    is Action.Toggle -> Action.Toggle(target)
    is Action.Adjust -> Action.Adjust(target, PropValue.Literal(oneOf(scalarOf(target, fields))))
    is Action.Navigate -> Action.Navigate(target)
    is Action.AppendRow -> action.copy(target = target)
    is Action.RemoveRow -> action.copy(target = target)
}

/** The literal scalar value an editable action carries (`SetState`'s value, `Adjust`'s delta), or null. */
fun scalarValueOf(action: Action): JsonPrimitive? = when (action) {
    is Action.SetState -> (action.value as? PropValue.Literal)?.value
    is Action.Adjust -> (action.by as? PropValue.Literal)?.value
    else -> null
}

/** [action] with its literal scalar value replaced (used by the value control); a no-op for valueless kinds. */
fun withScalarValue(action: Action, value: JsonPrimitive): Action = when (action) {
    is Action.SetState -> action.copy(value = PropValue.Literal(value))
    is Action.Adjust -> action.copy(by = PropValue.Literal(value))
    else -> action
}

/** The scalar type of an editable action's value control — the target field's type (`Adjust`/`SetState`). */
fun valueScalarType(action: Action, fields: List<StateField>): ScalarType? = when (action) {
    is Action.SetState -> resolveWritableScalar(action.target, fields)
    is Action.Adjust -> resolveWritableScalar(action.target, fields)
    else -> null
}

private fun StateField.scalarOr(default: ScalarType): ScalarType = (type as? StateType.Scalar)?.scalar ?: default

private fun scalarOf(name: String, fields: List<StateField>): ScalarType =
    resolveWritableScalar(name, fields) ?: ScalarType.STRING

private fun defaultScalar(scalar: ScalarType): JsonPrimitive = when (scalar) {
    ScalarType.STRING -> JsonPrimitive("")
    ScalarType.INT -> JsonPrimitive(0)
    ScalarType.FLOAT -> JsonPrimitive(0f)
    ScalarType.BOOL -> JsonPrimitive(false)
}

private fun oneOf(scalar: ScalarType): JsonPrimitive =
    if (scalar == ScalarType.FLOAT) JsonPrimitive(1f) else JsonPrimitive(1)

package viewforge.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One step of an event handler (ADR-035, #277): a **structured, closed** operation applied to declared state,
 * **never a code expression**. A handler is a `List<Action>` (see [Node.handlers]); each action is a member of
 * this sealed hierarchy, dispatched by a `when` at every layer — the renderer's C13 run-mode reducer, codegen,
 * and the inspector — so there is **no evaluator and no parser** anywhere (PF-4 stays literally true, exactly as
 * a [PropValue.StateBinding] path is *looked up*, never run). This is the whole security answer for interactivity:
 * mutating state and reacting to input are expressed as named, typed operations, not as user-authored Kotlin.
 *
 * A mutating action names its store with a [target] — a single-segment [PropValue.StateBinding]-style identifier
 * resolved by [resolveWritableTarget] against the owning [Screen.state] / [ComponentDef.state] to a **writable**
 * field of a compatible type; an unresolved target marks the node unverified (PF-6), never dynamic dispatch. A
 * closed hierarchy (PF-1), serialized with the global `kind` discriminator — so no member may declare a `kind`
 * property (the same VforgeJson constraint [StateType] / [SampleValue] observe).
 */
@Serializable
sealed interface Action {
    /** Assign [value] (a literal or a read [PropValue.StateBinding]) to the scalar state field named [target]. */
    @Serializable
    @SerialName("setState")
    data class SetState(val target: String, val value: PropValue) : Action

    /** Flip the boolean state field named [target] (`f = !f`). */
    @Serializable
    @SerialName("toggle")
    data class Toggle(val target: String) : Action

    /** Add [by] (a literal or read binding) to the numeric state field named [target] (`f += by`). */
    @Serializable
    @SerialName("adjust")
    data class Adjust(val target: String, val by: PropValue) : Action

    /** Append a [row] (each record field → a literal/binding value) to the list-of-record field named [target]. */
    @Serializable
    @SerialName("appendRow")
    data class AppendRow(val target: String, val row: Map<String, PropValue>) : Action

    /** Remove the row at [index] (a literal/binding Int) from the list-of-record field named [target]. */
    @Serializable
    @SerialName("removeRow")
    data class RemoveRow(val target: String, val index: PropValue) : Action

    /** Navigate to the screen identified by [screenId] (the structural hook for screen-to-screen nav, #214). */
    @Serializable
    @SerialName("navigate")
    data class Navigate(val screenId: String) : Action
}

/**
 * The state field an action writes to, or null for [Action.Navigate] (which targets a screen, not state). Lets a
 * validator, reducer, or generator collect/resolve targets without re-matching every variant.
 */
val Action.targetPath: String?
    get() = when (this) {
        is Action.SetState -> target
        is Action.Toggle -> target
        is Action.Adjust -> target
        is Action.AppendRow -> target
        is Action.RemoveRow -> target
        is Action.Navigate -> null
    }

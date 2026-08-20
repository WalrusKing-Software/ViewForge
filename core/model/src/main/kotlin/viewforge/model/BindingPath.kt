package viewforge.model

import kotlinx.serialization.json.JsonPrimitive

/**
 * Resolution of [PropValue.StateBinding] paths against screen state (ADR-034, #21). A binding path is a
 * **dotted identifier path** — `progress`, `item.title` — navigated by *structural lookup*, **never parsed or
 * evaluated as Kotlin** (PF-4): there is no evaluator here, only field access over declared state and, inside
 * a repeat template, the current record. These functions are pure and Compose-free so the renderer (resolving
 * against sample values), the generator (resolving shapes to emit member access), and the editor (validating a
 * binding) all share one authoritative answer.
 *
 * Two scopes exist at any point in the tree: the screen's [StateField]s, and — inside a [Repeater] template —
 * an `item` record (its fields for shape, its row for a sample value).
 */

/**
 * The segments of a binding [path] (`a.b.c` → `[a, b, c]`), or null if [path] is empty or any segment is not a
 * legal identifier. Purely structural: this validates the path *shape*, it does not resolve it against state.
 */
fun parseBindingPath(path: String): List<String>? {
    if (path.isEmpty()) return null
    val segments = path.split('.')
    if (segments.any { !isBindingIdentifier(it) }) return null
    return segments
}

/** A path segment / state name is a legal identifier: a letter or `_`, then letters, digits, or `_` (GC-3). */
fun isBindingIdentifier(s: String): Boolean =
    s.isNotEmpty() && (s[0].isLetter() || s[0] == '_') && s.all { it.isLetterOrDigit() || it == '_' }

/** The [StateField]s a binding can see at a point in the tree, plus the current repeat item's record shape. */
data class BindingTypeScope(val fields: List<StateField>, val itemFields: List<RecordField>? = null)

/** The [StateField]s in scope, plus the current repeat item's row of sample values (present inside a template). */
data class BindingValueScope(val fields: List<StateField>, val itemRow: Map<String, SampleValue>? = null)

/**
 * The scalar type a [path] resolves to under [scope], or null if it does not resolve to a scalar — used to
 * validate a binding and to check prop-type compatibility. A single segment names a scalar screen field;
 * `item.<field>` names a scalar field of the enclosing repeat's record. A list field, an unknown name, an
 * out-of-scope `item`, or a wrong arity all yield null (the caller then shows a placeholder / rejects).
 */
fun resolveBindingType(path: String, scope: BindingTypeScope): ScalarType? {
    val segs = parseBindingPath(path) ?: return null
    return when {
        segs[0] == Repeater.ITEM_SCOPE ->
            if (segs.size == 2) scope.itemFields?.firstOrNull { it.name == segs[1] }?.scalarOrNull else null
        segs.size == 1 -> (scope.fields.firstOrNull { it.name == segs[0] }?.type as? StateType.Scalar)?.scalar
        else -> null
    }
}

/**
 * The sample scalar a [path] resolves to under [scope], or null if it does not resolve — what the canvas
 * renders for a scalar binding (ADR-034). Mirrors [resolveBindingType] but returns the design-time value:
 * a scalar screen field's sample, or the current item row's value for `item.<field>`.
 */
fun resolveSampleScalar(path: String, scope: BindingValueScope): JsonPrimitive? {
    val segs = parseBindingPath(path) ?: return null
    return when {
        segs[0] == Repeater.ITEM_SCOPE -> if (segs.size == 2) scope.itemRow?.get(segs[1]).scalarValue else null
        segs.size == 1 -> (scope.fields.firstOrNull { it.name == segs[0] }?.sample as? SampleValue.Scalar)?.value
        else -> null
    }
}

/**
 * The list-of-record [StateField] a repeat's `source` [path] names, or null if [path] is not a single segment
 * naming a [StateType.ListOfRecord] field. The renderer iterates its [SampleValue.Rows]; the editor reads its
 * [StateType.ListOfRecord.fields] to build the `item` scope for the template.
 */
fun resolveListSource(path: String, fields: List<StateField>): StateField? {
    val segs = parseBindingPath(path) ?: return null
    if (segs.size != 1) return null
    val field = fields.firstOrNull { it.name == segs[0] } ?: return null
    return if (field.type is StateType.ListOfRecord) field else null
}

/**
 * The **writable** state field a mutating [Action]'s [path] names (ADR-035, #277), or null if it does not name a
 * declared field. A target is a single top-level identifier naming a [StateField] in the owner's state — an
 * `item.*` path is *not* writable (a repeat row is design-time sample data, not a store). Purely structural, no
 * evaluation (PF-4): the shared authority the reducer, generator, and inspector use to validate a handler target.
 */
fun resolveWritableTarget(path: String, fields: List<StateField>): StateField? {
    val segs = parseBindingPath(path) ?: return null
    if (segs.size != 1) return null
    return fields.firstOrNull { it.name == segs[0] }
}

/**
 * The scalar type an assignable target [path] names (for [Action.SetState] / [Action.Toggle] / [Action.Adjust]),
 * or null when [path] does not name a top-level *scalar* field. A list-of-record target (for [Action.AppendRow] /
 * [Action.RemoveRow]) yields null here — resolve it with [resolveWritableTarget] and check for [StateType.ListOfRecord].
 */
fun resolveWritableScalar(path: String, fields: List<StateField>): ScalarType? =
    (resolveWritableTarget(path, fields)?.type as? StateType.Scalar)?.scalar

/**
 * The record fields of the list a repeat `source` [path] names, in **either** scope (nested lists, #255): a
 * single segment names a top-level [StateType.ListOfRecord] screen field; `item.<field>` names a nested list
 * field of the enclosing repeat's record ([BindingTypeScope.itemFields]). Null when [path] does not name a list.
 * The shape-side companion of [resolveListRows]; used by the renderer and inspector to build the `item` scope.
 */
fun resolveListShape(path: String, scope: BindingTypeScope): List<RecordField>? {
    val segs = parseBindingPath(path) ?: return null
    val type = when {
        segs.size == 1 -> scope.fields.firstOrNull { it.name == segs[0] }?.type
        segs.size == 2 && segs[0] == Repeater.ITEM_SCOPE -> scope.itemFields?.firstOrNull { it.name == segs[1] }?.type
        else -> null
    }
    return (type as? StateType.ListOfRecord)?.fields
}

/**
 * The sample rows of the list a repeat `source` [path] names, in **either** scope (nested lists, #255) — the
 * value-side companion of [resolveListShape]. A top-level field's rows come from its [SampleValue.Rows] sample;
 * `item.<field>` rows come from the current row's nested cell. Null when [path] does not resolve to list rows.
 */
fun resolveListRows(path: String, scope: BindingValueScope): List<Map<String, SampleValue>>? {
    val segs = parseBindingPath(path) ?: return null
    val sample = when {
        segs.size == 1 -> scope.fields.firstOrNull { it.name == segs[0] }?.sample
        segs.size == 2 && segs[0] == Repeater.ITEM_SCOPE -> scope.itemRow?.get(segs[1])
        else -> null
    }
    return (sample as? SampleValue.Rows)?.rows
}

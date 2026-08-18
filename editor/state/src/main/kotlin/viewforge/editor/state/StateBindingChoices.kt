package viewforge.editor.state

import viewforge.model.Node
import viewforge.model.PropType
import viewforge.model.RecordField
import viewforge.model.Repeater
import viewforge.model.ScalarType
import viewforge.model.StateField
import viewforge.model.StateType
import viewforge.model.allChildren
import viewforge.model.resolveListSource

/**
 * Pure editor-side resolution of the read-only state paths a prop can bind to (ADR-034, #21). Kept out of
 * the composable inspector so the "what can I bind here" logic is unit-tested without a composition. Two
 * scopes are visible at a node (mirroring core/model's [viewforge.model.BindingTypeScope]): the screen's
 * scalar [StateField]s, and — inside a [Repeater] template — the current record's fields as `item.<field>`.
 * Nested repeats are out of scope this slice, so only the *nearest* enclosing repeat contributes.
 */

/** One offer in the inspector's "bind to data" menu: the dotted [path], its declared [scalar] type, and a [label]. */
data class BindingChoice(val path: String, val scalar: ScalarType, val label: String)

/**
 * Every scalar path [node] can bind to, given the active [screenState]: each scalar screen field by name,
 * plus `item.<field>` for each scalar field of the list [node]'s nearest enclosing [Repeater] iterates.
 * [root] is the active edit surface; a null root or a node outside it yields only the screen scalars.
 */
fun bindablePaths(root: Node?, node: Node, screenState: List<StateField>): List<BindingChoice> {
    val screenScalars = screenState.mapNotNull { field ->
        (field.type as? StateType.Scalar)?.let { BindingChoice(field.name, it.scalar, field.name) }
    }
    val itemFields = enclosingItemFields(root, node.id, screenState)
    val itemScalars = itemFields.map { rf ->
        BindingChoice("${Repeater.ITEM_SCOPE}.${rf.name}", rf.scalar, "${Repeater.ITEM_SCOPE}.${rf.name}")
    }
    return screenScalars + itemScalars
}

/**
 * The record fields of the nearest [Repeater] ancestor of [id] whose `source` resolves against [screenState],
 * or empty when [id] sits in no repeat (or the source doesn't resolve). Walks the root→node ancestor chain and
 * takes the *closest* repeat, so a template's own items win over an outer scope.
 */
private fun enclosingItemFields(
    root: Node?,
    id: viewforge.model.NodeId,
    screenState: List<StateField>,
): List<RecordField> {
    val chain = root?.let { pathTo(it, id) } ?: return emptyList()
    // Nearest ancestor first (exclude the node itself, which is `chain.last()`).
    val repeat = chain.dropLast(1).lastOrNull { it.type == Repeater.TYPE } ?: return emptyList()
    val source = Repeater.sourceOf(repeat) ?: return emptyList()
    val field = resolveListSource(source, screenState) ?: return emptyList()
    return (field.type as? StateType.ListOfRecord)?.fields ?: emptyList()
}

/** The chain of nodes from [root] down to (and including) the node with [id], or null if [id] is not in the tree. */
private fun pathTo(root: Node, id: viewforge.model.NodeId): List<Node>? {
    if (root.id == id) return listOf(root)
    for (child in root.allChildren()) {
        pathTo(child, id)?.let { return listOf(root) + it }
    }
    return null
}

/**
 * Whether a prop of [type] can carry a read-only state binding at all (value-like scalars only — Color,
 * Enum, Resource, Typography and Shape have no scalar state to bind). Mirrors the gate on parameter promotion.
 */
fun isBindableProp(type: PropType): Boolean = type in BINDABLE_PROP_TYPES

private val BINDABLE_PROP_TYPES = setOf(PropType.String, PropType.Int, PropType.Float, PropType.Bool, PropType.Dp)

/**
 * Whether a [scalar] state value is an acceptable binding for a prop of [propType]. Forgiving on numbers — a
 * numeric prop (Int/Float/Dp) accepts either INT or FLOAT — since a read-only preview widens freely; String
 * takes STRING and Bool takes BOOL. A non-bindable prop accepts nothing.
 */
fun acceptsScalar(propType: PropType, scalar: ScalarType): Boolean = when (propType) {
    PropType.String -> scalar == ScalarType.STRING
    PropType.Bool -> scalar == ScalarType.BOOL
    PropType.Int, PropType.Float, PropType.Dp -> scalar == ScalarType.INT || scalar == ScalarType.FLOAT
    else -> false
}

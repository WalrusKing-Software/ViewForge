package viewforge.editor.panels

import viewforge.model.Node
import viewforge.model.allChildren

/**
 * Pure tree-search logic for the layers panel (T6, #122) — Compose-free, unit-tested without a UI harness.
 * A query matches a node by the same text the tree shows via [displayLabel] (user-given name, else a
 * component instance's resolved name, else short type) or by its raw short type, case-insensitive substring.
 * The caller supplies [componentName] (e.g. `state.componentOfInstance(node)?.name`) so this stays
 * Project-free; the default resolver keeps a bare instance searchable by its raw `userComponent` type.
 */

/** Whether [node] matches [query] by its displayed label or short type (case-insensitive). A blank query matches nothing. */
internal fun nodeMatchesQuery(node: Node, query: String, componentName: (Node) -> String? = { null }): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return false
    return displayLabel(node, componentName(node)).lowercase().contains(needle) ||
        shortTypeName(node.type).lowercase().contains(needle)
}

/**
 * The ids to keep when filtering [root] by [query]: every node that [nodeMatchesQuery] plus all of its
 * ancestors, so a deep match stays reachable with its full path shown. Returns null for a blank query
 * (meaning "no filter" — the tree shows in full); an empty set means the query matched nothing.
 */
internal fun searchKeepSet(root: Node, query: String, componentName: (Node) -> String? = { null }): Set<String>? {
    if (query.trim().isEmpty()) return null
    val keep = mutableSetOf<String>()
    collectKeep(root, query, ArrayList(), keep, componentName)
    return keep
}

private fun collectKeep(
    node: Node,
    query: String,
    ancestry: MutableList<String>,
    keep: MutableSet<String>,
    componentName: (Node) -> String?,
) {
    if (nodeMatchesQuery(node, query, componentName)) {
        keep += node.id.value
        keep += ancestry // every ancestor stays so the path to this match is shown
    }
    ancestry.add(node.id.value)
    node.allChildren().forEach { collectKeep(it, query, ancestry, keep, componentName) }
    ancestry.removeAt(ancestry.size - 1)
}

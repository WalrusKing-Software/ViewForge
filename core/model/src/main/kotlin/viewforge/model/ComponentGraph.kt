package viewforge.model

import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure queries over the user-component reference graph (DATA_MODEL §4, PF-3). A component *references*
 * another when its tree contains a `vforge.userComponent` instance of it; a component must never
 * reference itself directly or transitively (a cycle would recurse forever in the renderer and
 * generator). Load-time validation forbids cycles, but the editor must also refuse to *create* one
 * mid-edit — hence these are shared, Compose-free, and unit-tested independently of both.
 */

/** Every component id referenced by an instance anywhere in this subtree (self included), deduplicated. */
fun Node.referencedComponentIds(): Set<String> {
    val here = UserComponent.componentIdOf(this)?.let { setOf(it) } ?: emptySet()
    return allChildren().fold(here) { acc, child -> acc + child.referencedComponentIds() }
}

/**
 * The transitive closure of user components that the component [fromComponentId] depends on — every
 * component reachable from its root (via [referencedComponentIds], transitively), **excluding the
 * component itself** — in breadth-first discovery order (references sorted at each step for a
 * deterministic result). An empty list means the component is self-contained; `null` means a referenced
 * id does **not** resolve to a component in this project (a dangling reference), which cannot be bundled
 * self-contained, so the caller refuses it rather than storing a broken closure (#234, ADR-033). Backs
 * capturing a nested component into the cross-project library. Cycle-safe via a visited set, though a
 * valid document never contains one (PF-3).
 */
fun Project.reachableComponents(fromComponentId: String): List<ComponentDef>? {
    val byId = components.associateBy { it.id }
    val start = byId[fromComponentId] ?: return null
    val result = LinkedHashMap<String, ComponentDef>()
    val queue = ArrayDeque(listOf(start))
    val visited = hashSetOf(fromComponentId)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (refId in current.root.referencedComponentIds().sorted()) {
            val dep = byId[refId] ?: return null // dangling reference → not self-containable
            if (visited.add(refId)) {
                result[refId] = dep
                queue.add(dep)
            }
        }
    }
    return result.values.toList()
}

/**
 * A deep copy of this subtree with every `userComponent` instance whose referenced component id is a key
 * in [mapping] repointed at the mapped id; instances referencing anything **not** in [mapping] — and all
 * other nodes — are left untouched. Backs inserting a library component's dependency closure (#234), where
 * every component in the closure is copied under a fresh id and the instances that wire them together must
 * be rewritten to point at those copies. Pairs with [Node.withFreshIds] (which refreshes node/modifier ids
 * but deliberately leaves `componentId` references alone): apply both to relocate a whole closure.
 */
fun Node.remapComponentReferences(mapping: Map<String, String>): Node {
    val mapped = UserComponent.componentIdOf(this)?.let { mapping[it] }
    return copy(
        props = if (mapped != null) {
            props + (UserComponent.COMPONENT_ID_PROP to PropValue.Literal(JsonPrimitive(mapped)))
        } else {
            props
        },
        children = children.map { it.remapComponentReferences(mapping) },
        slots = slots.mapValues { (_, list) -> list.map { it.remapComponentReferences(mapping) } },
    )
}

/**
 * Whether inserting [inserted] into the tree of the component identified by [targetComponentId] would
 * close a cycle in the reference graph (PF-3). A cycle forms when the inserted subtree references —
 * directly or transitively — the very component being edited, including inserting an instance of that
 * component into itself.
 *
 * Returns false when [targetComponentId] is null: the edit surface is then a *screen*, and screens are
 * never referenced by anything, so no instance insert can point back at the surface. Also false when
 * [inserted] references no components at all (a framework built-in), the common case.
 */
fun Project.insertionWouldCycle(targetComponentId: String?, inserted: Node): Boolean {
    if (targetComponentId == null) return false
    val referenced = inserted.referencedComponentIds()
    if (referenced.isEmpty()) return false

    // Adjacency over existing components: id -> the component ids its own tree references (restricted to
    // components that exist). The insert adds edges targetComponentId -> each id in `referenced`, so a
    // cycle results iff the target is reachable from any of them (target itself counts, X -> ... -> target).
    val byId = components.associateBy { it.id }
    val edges: Map<String, Set<String>> =
        components.associate { c -> c.id to c.root.referencedComponentIds().filter { it in byId }.toSet() }

    val seen = HashSet<String>()
    fun reaches(from: String): Boolean {
        if (from == targetComponentId) return true
        if (!seen.add(from)) return false
        return edges[from].orEmpty().any(::reaches)
    }
    return referenced.any(::reaches)
}

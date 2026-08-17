package viewforge.model

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

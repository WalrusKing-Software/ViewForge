package viewforge.model

/**
 * Pure, immutable structural edits on the IR tree (DATA_MODEL §5, ARCHITECTURE §5). Stdlib-only and
 * Compose-free so the command layer, undo/redo, and drag-drop all share one authoritative set of
 * transforms rather than each re-deriving tree surgery.
 *
 * **Structural sharing is a requirement, not an optimization** (CLAUDE.md anti-patterns): every
 * operation rebuilds only the root→target path and returns the *same* instance for any subtree it did
 * not touch. Siblings stay referentially identical, which keeps recomposition skipping cheap and lets
 * callers cheaply detect "nothing changed". The tests assert this identity directly.
 *
 * Both the default [Node.children] region and every named [Node.slots] region are first-class: a
 * slotted child (a Scaffold's topBar, a Button's content) is edited by the same operations as a
 * default child, addressed by [ChildAddress.slot].
 */

/**
 * A position inside a parent node: the [index]-th entry of either its default children region
 * ([slot] == null) or its named slot [slot].
 *
 * **Index semantics.** [index] is the final position the child occupies. For a move
 * (remove-then-insert) the target index is interpreted against the list **after** the moved node has
 * been removed, which makes a move unambiguous and exactly self-inverting.
 */
data class ChildAddress(val parentId: NodeId, val slot: String?, val index: Int)

/**
 * The [ChildAddress] of [id] — the parent that directly holds it and where. Null if [id] is this
 * root (roots have no parent) or is absent. Depth-first, children before slots, matching [findById].
 */
fun Node.locate(id: NodeId): ChildAddress? {
    children.forEachIndexed { i, child -> if (child.id == id) return ChildAddress(this.id, null, i) }
    slots.forEach { (name, list) ->
        list.forEachIndexed { i, child -> if (child.id == id) return ChildAddress(this.id, name, i) }
    }
    children.forEach { child -> child.locate(id)?.let { return it } }
    slots.values.forEach { list -> list.forEach { child -> child.locate(id)?.let { return it } } }
    return null
}

/** True if [id] is this node or anywhere in its subtree — the guard against reparenting into self. */
fun Node.subtreeContains(id: NodeId): Boolean = findById(id) != null

/**
 * A copy with [child] inserted at [address], rebuilding only the root→parent path. The index is
 * clamped into `[0, size]`. Same instance if [ChildAddress.parentId] is absent.
 */
fun Node.insertChild(address: ChildAddress, child: Node): Node = transformNode(address.parentId) { parent ->
    parent.editRegion(address.slot) { list ->
        val i = address.index.coerceIn(0, list.size)
        list.subList(0, i) + child + list.subList(i, list.size)
    }
}

/**
 * A copy with the node [id] removed from wherever it sits, rebuilding only the affected path. Same
 * instance if [id] is absent (or is this root, which cannot be removed).
 */
fun Node.removeChild(id: NodeId): Node {
    val addr = locate(id) ?: return this
    return transformNode(addr.parentId) { parent ->
        parent.editRegion(addr.slot) { list -> list.filterIndexed { i, _ -> i != addr.index } }
    }
}

/**
 * A copy with the node [id] replaced by [replacement], rebuilding only the root→node path. Used for
 * in-place edits that keep a node's position (rename, flag toggles). Same instance if [id] is absent.
 */
fun Node.replaceNode(id: NodeId, replacement: Node): Node = transformNode(id) { replacement }

/**
 * A deep copy of this subtree with every [NodeId] and [ModifierEntry.id] freshly generated. Backs
 * duplicate and paste, where the clone must be a genuinely new node, never a second reference to an
 * existing id (D5: "fresh IDs generated").
 */
fun Node.withFreshIds(): Node = copy(
    id = NodeId.random(),
    modifiers = modifiers.map { it.copy(id = Ulid.next()) },
    children = children.map { it.withFreshIds() },
    slots = slots.mapValues { (_, list) -> list.map { it.withFreshIds() } },
)

/**
 * A copy of this node with prop [key] set to [value], or **removed** when [value] is null (used to
 * reset a prop to its default, I7). Returns the same instance if nothing changed. Only this node's
 * [Node.props] map is rebuilt; children and modifiers keep identity.
 */
fun Node.withProp(key: String, value: PropValue?): Node {
    val next = if (value == null) {
        if (key !in props) return this
        props - key
    } else {
        if (props[key] == value) return this
        props + (key to value)
    }
    return copy(props = next)
}

/** A copy of this node with its ordered modifier chain replaced. Same instance if [modifiers] is unchanged. */
fun Node.withModifiers(modifiers: List<ModifierEntry>): Node =
    if (modifiers == this.modifiers) this else copy(modifiers = modifiers)

/**
 * A copy of this [Project] with the screen [screenId]'s root transformed. Other screens keep their
 * identity, and if [transform] returns the same root instance the whole project is returned unchanged.
 */
fun Project.updateScreenRoot(screenId: String, transform: (Node) -> Node): Project {
    var changed = false
    val newScreens = screens.map { screen ->
        if (screen.id != screenId) return@map screen
        val newRoot = transform(screen.root)
        if (newRoot === screen.root) {
            screen
        } else {
            changed = true
            screen.copy(root = newRoot)
        }
    }
    return if (changed) copy(screens = newScreens) else this
}

// --- internals -----------------------------------------------------------------------------------

/**
 * Walk to the node with [id], apply [transform], and rebuild only the root→node path — the single
 * primitive all edits share. Untouched subtrees (and lists/maps that did not change) are returned by
 * identity, so `result === this` exactly when nothing matched.
 */
private fun Node.transformNode(id: NodeId, transform: (Node) -> Node): Node {
    if (this.id == id) return transform(this)
    val newChildren = children.transformEach(id, transform)
    val newSlots = slots.transformEach(id, transform)
    return if (newChildren === children && newSlots === slots) this else copy(children = newChildren, slots = newSlots)
}

/** Apply [edit] to this node's default children ([slot] == null) or named [slot]; identity-preserving. */
private inline fun Node.editRegion(slot: String?, edit: (List<Node>) -> List<Node>): Node {
    if (slot == null) {
        val next = edit(children)
        return if (next === children) this else copy(children = next)
    }
    val current = slots[slot] ?: emptyList()
    val next = edit(current)
    return if (next === current) this else copy(slots = slots + (slot to next))
}

/** Map each element through [transformNode], returning the same list instance when none changed. */
private fun List<Node>.transformEach(id: NodeId, transform: (Node) -> Node): List<Node> {
    var changed = false
    val result = map { child ->
        val next = child.transformNode(id, transform)
        if (next !== child) changed = true
        next
    }
    return if (changed) result else this
}

/** Map each slot list through [transformEach], returning the same map instance when none changed. */
private fun Map<String, List<Node>>.transformEach(id: NodeId, transform: (Node) -> Node): Map<String, List<Node>> {
    var changed = false
    val result = LinkedHashMap<String, List<Node>>(size)
    for ((name, list) in this) {
        val next = list.transformEach(id, transform)
        if (next !== list) changed = true
        result[name] = next
    }
    return if (changed) result else this
}

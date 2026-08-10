package viewforge.model

/**
 * Pure IR traversal helpers (DATA_MODEL §5). Compose-free and stdlib-only so selection, the tree
 * panel, and the inspector share one authoritative walk of the document rather than each re-deriving
 * it (ARCHITECTURE §4.4: the IR tree — not the render output — is the authority for what exists).
 *
 * Both [children] and every list in [slots] are traversed: a slotted child (a Scaffold's topBar, a
 * Button's content) is as real a node as a default-region child and must be findable and selectable.
 */

/** The node with [id] anywhere in this subtree (self included), or null. Depth-first, children then slots. */
fun Node.findById(id: NodeId): Node? {
    if (this.id == id) return this
    children.forEach { child -> child.findById(id)?.let { return it } }
    slots.values.forEach { list -> list.forEach { child -> child.findById(id)?.let { return it } } }
    return null
}

/** Every direct child of this node, from [children] and all [slots], in a stable order (children first). */
fun Node.allChildren(): List<Node> = children + slots.values.flatten()

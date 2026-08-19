package viewforge.model

/**
 * Pure queries about a document's interactivity (ADR-035, #277). A project is "interactive" once any node —
 * on any screen or in any component — carries an event handler ([Node.handlers]); that is the trigger for the
 * light, one-time per-project acknowledgment that generated code will now include mutable state and event
 * handlers. Compose-free and stdlib-only, like the rest of the model.
 */

/** True if any node in [this] node's subtree (itself, children, and slots) satisfies [predicate]. */
fun Node.anyInTree(predicate: (Node) -> Boolean): Boolean {
    if (predicate(this)) return true
    if (children.any { it.anyInTree(predicate) }) return true
    return slots.values.any { list -> list.any { it.anyInTree(predicate) } }
}

/** True if any node in this project — across every screen and component — declares an event handler (ADR-035). */
fun Project.hasInteractiveNodes(): Boolean {
    val hasHandler: (Node) -> Boolean = { it.handlers.isNotEmpty() }
    return screens.any { it.root.anyInTree(hasHandler) } || components.any { it.root.anyInTree(hasHandler) }
}

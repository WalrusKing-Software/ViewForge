package viewforge.model

/**
 * Resolution for a node's [responsive][Node.responsive] per-breakpoint overrides (ADR-030, #221). The
 * effective props at a breakpoint are the base [props][Node.props] with that breakpoint's overrides layered
 * on top — an override replaces a named prop; an un-overridden prop keeps its base value. The base props are
 * the default (the smallest, `compact` breakpoint), so a `null`/absent/unknown breakpoint resolves to the
 * base unchanged.
 *
 * Pure and framework-neutral: a breakpoint id is an opaque string to `core` (the set and its dp thresholds
 * are owned by the framework package's target, ADR-030), so this only overlays maps — it never interprets a
 * width. It is the shape render and codegen consume, resolved once up front before the value pipeline they
 * already have (the same approach as parameter binding, ADR-028) — no existing prop reader changes.
 */
fun effectiveProps(node: Node, breakpointId: String?): Map<String, PropValue> {
    val overrides = breakpointId?.let { node.responsive[it] }
    return if (overrides.isNullOrEmpty()) node.props else node.props + overrides
}

/**
 * A whole-tree pre-pass (children and slots) that overlays each node's [breakpointId] overrides onto its
 * base props via [effectiveProps] and clears [responsive][Node.responsive], returning a tree whose props are
 * already resolved for that breakpoint — the plain, override-free form the renderer/emitter walk. A tree with
 * no overrides is returned **structurally unchanged** (same instances), so a non-responsive document — and
 * every node in it — is unaffected and keeps structural sharing (recomposition skipping).
 */
fun Node.resolvedForBreakpoint(breakpointId: String?): Node {
    val resolvedChildren = children.map { it.resolvedForBreakpoint(breakpointId) }
    val resolvedSlots = slots.mapValues { (_, nodes) -> nodes.map { it.resolvedForBreakpoint(breakpointId) } }
    if (responsive.isEmpty() && resolvedChildren == children && resolvedSlots == slots) return this
    return copy(
        props = effectiveProps(this, breakpointId),
        responsive = emptyMap(),
        children = resolvedChildren,
        slots = resolvedSlots,
    )
}

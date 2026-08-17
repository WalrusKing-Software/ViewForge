package viewforge.editor.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import viewforge.model.ChildAddress
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.allChildren
import viewforge.model.findById

/**
 * Pure geometry behind canvas drag-to-reparent (FEATURES C7). Compose-free and unit-tested without a
 * UI harness, exactly like [hitTest]: it works over the window-space bounds the editor instrumentation
 * records ([NodeBounds]) and the IR tree, and never touches a composition. The gesture wiring and the
 * drop indicator live in [SelectionOverlay]; the legality gate stays
 * [EditorState.canDrop][viewforge.editor.state.EditorState.canDrop]. Because bounds come back already
 * scaled/panned through the canvas `graphicsLayer` (TECHNICAL_NOTES §5), everything here is in one
 * frame — window space — and stays correct at every zoom/pan level with no coordinate math.
 *
 * **Scope (v1):** default-children regions only. Named slots (a Scaffold's topBar) are not recorded as
 * separate geometry in [NodeBounds], so geometric slot targeting isn't reliable yet — slot reparenting
 * stays a tree-panel operation (T2). [canvasDropTarget] therefore filters to containers that accept
 * default children.
 */

/**
 * The deepest node under [point] (window space) that accepts default children, excluding the dragged
 * node and everything in its subtree (the cycle guard — you cannot drop a node into itself or a
 * descendant) and any **locked** node (per-node T4 — a locked node can't receive children, though a
 * container nested inside it still can). Children are tested before the node itself so the *innermost*
 * accepting container wins, matching [hitTest]'s deepest-first rule: hovering inside a nested container
 * drops **into** it.
 *
 * [draggedId] is null for a palette drag (P2a) — a brand-new node with no existing position — in which
 * case nothing is excluded.
 */
fun canvasDropTarget(
    rects: Map<String, Rect>,
    root: Node,
    draggedId: NodeId?,
    point: Offset,
    acceptsChildren: (String) -> Boolean,
): NodeId? {
    if (root.hidden) return null // hidden nodes are neither rendered nor droppable-into
    if (root.id == draggedId) return null // don't descend into (or target) the dragged subtree
    root.allChildren().forEach { child ->
        canvasDropTarget(rects, child, draggedId, point, acceptsChildren)?.let { return it }
    }
    // A locked node is protected (per-node T4): it can't receive children itself. We still descend into it
    // above, so a container *nested inside* a locked node stays a valid target (its own child list, not the
    // locked node's, is what changes); only the locked node is refused as the target here.
    if (root.locked) return null
    if (!acceptsChildren(root.type)) return null
    val rect = rects[root.id.value] ?: return null
    return if (rect.contains(point)) root.id else null
}

/**
 * The insertion index for [point] among a container's [childRects] (window space, in child-list
 * order, with the dragged node already excluded). The arrangement axis is **inferred geometrically**
 * from the spread of child centres — vertical (Column) when the y-spread dominates, horizontal (Row)
 * otherwise — so the editor never hard-codes a component's orientation (ADR-015). The index is the
 * count of children whose centre precedes [point] along that axis, i.e. the post-removal position
 * [MoveNode][viewforge.command.MoveNode] expects. Empty list → 0.
 */
fun insertionIndex(childRects: List<Rect>, point: Offset): Int {
    if (childRects.isEmpty()) return 0
    return if (isVerticalArrangement(childRects)) {
        childRects.count { it.center.y < point.y }
    } else {
        childRects.count { it.center.x < point.x }
    }
}

/**
 * The full drop address for [point]: the deepest accepting container under it plus the geometric
 * insertion index within that container's default region. Null when no accepting container is under
 * the pointer (an empty-canvas or into-a-leaf drop). Legality is still confirmed by
 * [EditorState.canDrop][viewforge.editor.state.EditorState.canDrop]; by construction the returned
 * address already satisfies it (accepting parent, dragged subtree excluded). [draggedId] is null for a
 * palette drag (nothing to exclude from the index).
 */
fun canvasDropAddress(
    rects: Map<String, Rect>,
    root: Node,
    draggedId: NodeId?,
    point: Offset,
    acceptsChildren: (String) -> Boolean,
): ChildAddress? {
    val targetId = canvasDropTarget(rects, root, draggedId, point, acceptsChildren) ?: return null
    val target = root.findById(targetId) ?: return null
    val childRects = target.children.filter { it.id != draggedId }.mapNotNull { rects[it.id.value] }
    return ChildAddress(targetId, null, insertionIndex(childRects, point))
}

/**
 * Whether [rects] read as a vertical (column) arrangement: true when their centres spread at least as
 * far along y as along x. Ties and fewer than two rects fall back to vertical (the Column-biased
 * default), since a single child gives no axis to infer.
 */
internal fun isVerticalArrangement(rects: List<Rect>): Boolean {
    if (rects.size < 2) return true
    val xs = rects.map { it.center.x }
    val ys = rects.map { it.center.y }
    return (ys.max() - ys.min()) >= (xs.max() - xs.min())
}

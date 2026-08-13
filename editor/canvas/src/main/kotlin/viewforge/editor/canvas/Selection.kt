package viewforge.editor.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import viewforge.editor.state.EditorState
import viewforge.model.ChildAddress
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.allChildren
import viewforge.model.findById

/**
 * The per-node spatial index behind hit-testing (ARCHITECTURE §4.4, ADR-009). Each rendered node's
 * editor instrumentation writes its window-space bounds here via `onGloballyPositioned`.
 *
 * Keyed by [NodeId.value] and held as snapshot state so the selection outline redraws when a node's
 * bounds change (scroll, resize, recomposition — FEATURES C4). Entries are never explicitly evicted:
 * the IR tree is the authority for what exists (a stale entry for a deleted node is simply never
 * queried), so the index only ever answers "where is this node that the tree says exists?".
 */
class NodeBounds {
    private val rects = mutableStateMapOf<String, Rect>()

    /** Record (window-space) bounds for a node; a no-op if unchanged, to avoid a recomposition loop. */
    fun record(id: NodeId, rect: Rect) {
        if (rects[id.value] != rect) rects[id.value] = rect
    }

    fun boundsOf(id: NodeId): Rect? = rects[id.value]

    /** A plain snapshot for pure hit-testing off the composition thread. */
    fun snapshot(): Map<String, Rect> = rects.toMap()
}

/**
 * The deepest node in [root]'s subtree whose recorded bounds contain [point] (window space), or null
 * (a click on empty canvas → deselect). Children and slot children are tested before the node itself,
 * so the *innermost* hit wins (FEATURES C2). Pure and Compose-free: unit-tested without a UI harness.
 */
fun hitTest(rects: Map<String, Rect>, root: Node, point: Offset): NodeId? {
    if (root.hidden) return null // hidden nodes are neither rendered nor selectable (DATA_MODEL §5)
    root.allChildren().forEach { child -> hitTest(rects, child, point)?.let { return it } }
    val rect = rects[root.id.value]
    return if (rect != null && rect.contains(point)) root.id else null
}

/**
 * The one canonical canvas↔screen transform (TECHNICAL_NOTES §5): all coordinate math goes through
 * here so it survives future zoom/pan instead of being scattered inline at call sites. Bounds are
 * captured in window space; the overlay works in its own local space; this bridges the two using the
 * overlay's own [LayoutCoordinates]. At M3 there is no zoom, so it is a pure translation — the single
 * place a scale factor will later be applied.
 */
private class CanvasTransform(private val overlay: LayoutCoordinates) {
    fun windowToLocal(point: Offset): Offset = overlay.windowToLocal(point)

    fun localToWindow(point: Offset): Offset = overlay.localToWindow(point)

    fun rectToLocal(rect: Rect): Rect {
        val topLeft = windowToLocal(rect.topLeft)
        return Rect(topLeft, Size(rect.width, rect.height))
    }
}

/**
 * Transient state for a canvas drag-to-reparent gesture (C7), the geometric-drop counterpart of the
 * tree panel's `TreeDragState`. It holds the dragged node and the resolved drop, recomputed on each
 * drag move from the window-space bounds and the pure [canvasDropAddress]; validity is confirmed by
 * [EditorState.canDrop] and the move committed via [EditorState.moveNode], so it shares the exact drop
 * rules and post-removal index semantics the tree uses. All positions are window space (the overlay is
 * unscaled), so the resolution stays correct at every zoom/pan level.
 *
 * The visual feedback is a green insertion caret + target outline when the drop is legal, or a red
 * outline of whatever the pointer is over when it isn't (into a non-container or the dragged node's own
 * subtree) — "rejected visually" rather than as an error afterward (TECHNICAL_NOTES §6).
 */
internal class CanvasDragState(private val state: EditorState, private val bounds: NodeBounds) {
    var draggingId by mutableStateOf<NodeId?>(null)
        private set
    var dropValid by mutableStateOf(false)
        private set

    /** The node whose outline the feedback draws — the target when valid, the rejected node otherwise. */
    var outlineId by mutableStateOf<NodeId?>(null)
        private set

    /** The insertion-caret endpoints (window space), or null for an into-empty-container drop (outline only). */
    var caret by mutableStateOf<Pair<Offset, Offset>?>(null)
        private set

    private var dropAddress: ChildAddress? = null

    /** Begin dragging [id] (the deepest hit under the press); callers must exclude the root and locked nodes. */
    fun begin(id: NodeId) {
        draggingId = id
    }

    /** Resolve the drop for a window-space [point] and update the feedback; a no-op if not dragging. */
    fun update(point: Offset) {
        val root = state.activeEditRoot
        val dragged = draggingId ?: return
        if (root == null) return clearTarget()
        val rects = bounds.snapshot()
        val address = canvasDropAddress(rects, root, dragged, point, state.catalog::acceptsChildren)
        if (address != null && state.canDrop(dragged, address)) {
            dropAddress = address
            dropValid = true
            outlineId = address.parentId
            caret = insertionCaret(rects, root, dragged, address)
        } else {
            // No legal target: show a red outline of whatever is under the pointer (the rejection).
            dropAddress = null
            dropValid = false
            caret = null
            outlineId = hitTest(rects, root, point)
        }
    }

    /** Commit the move if the current target is legal, then reset. Called on drag end. */
    fun commit() {
        val id = draggingId
        val addr = dropAddress
        if (dropValid && id != null && addr != null) state.moveNode(id, addr)
        reset()
    }

    fun reset() {
        draggingId = null
        clearTarget()
    }

    private fun clearTarget() {
        dropValid = false
        outlineId = null
        caret = null
        dropAddress = null
    }
}

/**
 * The insertion caret for [address] in window space: a line across the target at the gap the index
 * names, along the container's inferred axis. Null when the target's default region is empty (the
 * feedback falls back to the target outline alone). Shared by node-drag ([CanvasDragState]) and the
 * palette drag (P2a); [draggedId] is null for the latter (nothing to exclude).
 */
internal fun insertionCaret(
    rects: Map<String, Rect>,
    root: Node,
    draggedId: NodeId?,
    address: ChildAddress,
): Pair<Offset, Offset>? {
    val target = root.findById(address.parentId) ?: return null
    val box = rects[address.parentId.value] ?: return null
    val childRects = target.children.filter { it.id != draggedId }.mapNotNull { rects[it.id.value] }
    if (childRects.isEmpty()) return null
    val i = address.index.coerceIn(0, childRects.size)
    return if (isVerticalArrangement(childRects)) {
        val y = when (i) {
            0 -> childRects.first().top
            childRects.size -> childRects.last().bottom
            else -> (childRects[i - 1].bottom + childRects[i].top) / 2f
        }
        Offset(box.left, y) to Offset(box.right, y)
    } else {
        val x = when (i) {
            0 -> childRects.first().left
            childRects.size -> childRects.last().right
            else -> (childRects[i - 1].right + childRects[i].left) / 2f
        }
        Offset(x, box.top) to Offset(x, box.bottom)
    }
}

/**
 * The transparent editor chrome layer above the rendered UI (ARCHITECTURE §4.4). It owns all pointer
 * input for editing — click to select the deepest node, hover to preview, scroll to zoom, space-drag
 * to pan (C5), drag a node to reparent/reorder (C7) — and draws selection, hover, and drop outlines.
 * Because it sits *above* the render output and
 * never draws into it, editor chrome can never affect the user UI's layout.
 *
 * The overlay is deliberately left outside the content's zoom/pan `graphicsLayer`, so its pointer
 * coordinates and drag deltas are already in window space: a pan drag maps 1:1 to the cursor, and the
 * unchanged window-space [hitTest] stays correct because the node bounds it tests against come back
 * from `boundsInWindow` already scaled by that same layer.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SelectionOverlay(state: EditorState, root: Node, bounds: NodeBounds, modifier: Modifier = Modifier) {
    var transform by remember { mutableStateOf<CanvasTransform?>(null) }
    var hovered by remember { mutableStateOf<NodeId?>(null) }
    // Live keyboard-modifier state, read at tap time to tell a plain click (replace selection) from a
    // ctrl/cmd- or shift-click (toggle into a multi-selection, C10).
    val windowInfo = LocalWindowInfo.current
    // The last tapped node and when — for manual double-tap detection that keeps single-tap selection
    // instant (unlike detectTapGestures' onDoubleTap, which delays every tap). Reset per edit surface.
    var lastTap by remember(root) { mutableStateOf<Pair<NodeId, Long>?>(null) }
    val drag = remember(root) { CanvasDragState(state, bounds) }

    // Palette drag-to-canvas (P2a): while a palette drag is live, resolve its drop against the same
    // canvas geometry as a node drag, using the pointer position the palette publishes in window space.
    // There is no dragged node to exclude (it doesn't exist yet), hence the null id. The resolved
    // address is published back to state so the palette can commit it as an AddNode on release.
    val dragType = state.paletteDragType
    val dragX = state.paletteDragX
    val dragY = state.paletteDragY
    var paletteAddress: ChildAddress? = null
    var paletteFeedback: PaletteDropFeedback? = null
    if (dragType != null && dragX != null && dragY != null) {
        val rects = bounds.snapshot()
        val point = Offset(dragX, dragY)
        paletteAddress = canvasDropAddress(rects, root, null, point, state.catalog::acceptsChildren)
        paletteFeedback = PaletteDropFeedback(
            outlineId = paletteAddress?.parentId ?: hitTest(rects, root, point),
            caret = paletteAddress?.let { insertionCaret(rects, root, null, it) },
            valid = paletteAddress != null,
        )
    }
    if (dragType != null) {
        SideEffect { state.resolvePaletteDrop(paletteAddress) }
    }

    Canvas(
        modifier
            .onGloballyPositioned { transform = CanvasTransform(it) }
            .pointerInput(root) {
                val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
                detectTapGestures { local ->
                    // While panning (space held) a press is a pan, not a selection — ignore it here.
                    if (state.isSpaceHeld) return@detectTapGestures
                    val point = transform?.localToWindow(local) ?: local
                    val hit = hitTest(bounds.snapshot(), root, point)
                    val mods = windowInfo.keyboardModifiers
                    // Ctrl/Cmd- or Shift-click toggles the hit in/out of a multi-selection (C10). The canvas
                    // has no natural order, so Shift is additive like Ctrl here — a range is a tree gesture.
                    // A modified click on empty canvas leaves the selection untouched.
                    if (mods.isCtrlPressed || mods.isMetaPressed || mods.isShiftPressed) {
                        hit?.let { state.toggleSelection(it) }
                        lastTap = null
                        return@detectTapGestures
                    }
                    state.select(hit) // plain click selects instantly (replacing) on every tap
                    // A quick second tap on the same instance enters its component (#68); double-tapping
                    // anything else just re-selects it.
                    val now = System.currentTimeMillis()
                    val prev = lastTap
                    lastTap = if (hit != null && prev?.first == hit && now - prev.second < doubleTapMs) {
                        root.findById(hit)?.let { state.openInstanceComponent(it) }
                        null
                    } else {
                        hit?.let { it to now }
                    }
                }
            }
            // Drag a node to reparent/reorder (C7). Inactive while space-panning, so the two drags are
            // mutually exclusive; a press with no movement falls through to the tap-to-select above.
            .pointerInput(root, state.isSpaceHeld) {
                if (state.isSpaceHeld) return@pointerInput
                detectDragGestures(
                    onDragStart = { local ->
                        val point = transform?.localToWindow(local) ?: local
                        val hit = hitTest(bounds.snapshot(), root, point) ?: return@detectDragGestures
                        // The root has nowhere to move to, and locked nodes don't drag.
                        if (hit != root.id && root.findById(hit)?.locked != true) drag.begin(hit)
                    },
                    onDrag = { change, _ ->
                        if (drag.draggingId == null) return@detectDragGestures
                        change.consume()
                        drag.update(transform?.localToWindow(change.position) ?: change.position)
                    },
                    onDragEnd = { drag.commit() },
                    onDragCancel = { drag.reset() },
                )
            }
            // Space-drag to pan (C5). Keyed on the flag so the gesture re-arms when pan mode toggles;
            // deltas are window-space (the overlay is unscaled), so panBy tracks the cursor exactly.
            .pointerInput(state.isSpaceHeld) {
                if (!state.isSpaceHeld) return@pointerInput
                detectDragGestures { change, drag ->
                    change.consume()
                    state.panBy(drag.x, drag.y)
                }
            }
            .pointerInput(root) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            // Scroll to zoom (C5): one notch per event, direction from the wheel sign.
                            PointerEventType.Scroll -> {
                                val dy = event.changes.first().scrollDelta.y
                                if (dy != 0f) state.zoomBy(if (dy < 0f) ZOOM_IN_FACTOR else 1f / ZOOM_IN_FACTOR)
                            }
                            PointerEventType.Exit -> hovered = null
                            else -> {
                                val local = event.changes.first().position
                                val point = transform?.localToWindow(local) ?: local
                                hovered = hitTest(bounds.snapshot(), root, point)
                            }
                        }
                    }
                }
            },
    ) {
        val t = transform ?: return@Canvas
        // Palette drag wins the overlay while it's live: show its drop feedback and nothing else.
        paletteFeedback?.let { fb ->
            val color = if (fb.valid) DROP_OK else DROP_BAD
            fb.outlineId?.let { id ->
                bounds.boundsOf(id)?.let { drawOutline(t.rectToLocal(it), color, DROP_STROKE) }
            }
            fb.caret?.let { (a, b) ->
                drawLine(color, t.windowToLocal(a), t.windowToLocal(b), strokeWidth = DROP_STROKE)
            }
            return@Canvas
        }
        if (drag.draggingId != null) {
            // Mid-drag: show only drop feedback (green = legal, red = rejected) so it isn't lost among
            // the hover/selection outlines. The caret marks the exact insertion gap in window space.
            val color = if (drag.dropValid) DROP_OK else DROP_BAD
            drag.outlineId?.let { id ->
                bounds.boundsOf(id)?.let { drawOutline(t.rectToLocal(it), color, DROP_STROKE) }
            }
            drag.caret?.let { (a, b) ->
                drawLine(color, t.windowToLocal(a), t.windowToLocal(b), strokeWidth = DROP_STROKE)
            }
            return@Canvas
        }
        // Hover first, selection on top: when a node is both, the selection outline wins visually.
        hovered?.takeIf { !state.isSelected(it) }?.let { id ->
            bounds.boundsOf(id)?.let { drawOutline(t.rectToLocal(it), HOVER_COLOR, HOVER_STROKE) }
        }
        // Every selected node is outlined (C10); the secondary selections draw faded, the primary solid
        // and on top so it reads as the focused node.
        val primary = state.selectedId
        state.selectedIds.forEach { id ->
            if (id == primary) return@forEach
            bounds.boundsOf(id)?.let { drawOutline(t.rectToLocal(it), MULTI_SELECTION_COLOR, SELECTION_STROKE) }
        }
        primary?.let { id ->
            bounds.boundsOf(id)?.let { drawOutline(t.rectToLocal(it), SELECTION_COLOR, SELECTION_STROKE) }
        }
    }
}

private fun DrawScope.drawOutline(rect: Rect, color: Color, width: Float) {
    drawRect(
        color = color,
        topLeft = rect.topLeft,
        size = rect.size,
        style = Stroke(width = width),
    )
}

private val SELECTION_COLOR = Color(0xFF1E88E5)

// Faded blue for the non-primary members of a multi-selection (C10), so the solid primary stands out.
private val MULTI_SELECTION_COLOR = Color(0x991E88E5)
private val HOVER_COLOR = Color(0x881E88E5)
private const val SELECTION_STROKE = 2f // px; a thin editor outline, deliberately not layout-affecting
private const val HOVER_STROKE = 1f

// Drop feedback (C7), matching the tree panel's palette: green = legal drop, red = rejected.
private val DROP_OK = Color(0xFF43A047)
private val DROP_BAD = Color(0xFFB00020)
private const val DROP_STROKE = 2f

/** The overlay's drop feedback for a live palette drag (P2a): a caret at the gap plus a target outline. */
private data class PaletteDropFeedback(val outlineId: NodeId?, val caret: Pair<Offset, Offset>?, val valid: Boolean)

/** Per-scroll-notch zoom multiplier; finer than the menu/keyboard step so the wheel feels continuous. */
private const val ZOOM_IN_FACTOR = 1.1f

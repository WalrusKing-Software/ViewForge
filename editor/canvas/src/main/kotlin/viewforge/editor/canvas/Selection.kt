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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import viewforge.editor.state.CanvasViewport
import viewforge.editor.state.EditorState
import viewforge.model.ChildAddress
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.allChildren
import viewforge.model.findById
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The per-node spatial index behind hit-testing (ARCHITECTURE §4.4, ADR-009). Each rendered node's
 * editor instrumentation writes its **content-space** bounds here via `onGloballyPositioned` — bounds in
 * the frame's unscaled coordinate space, invariant to the zoom/pan (#116). The overlay maps them to and
 * from screen space with the live viewport transform ([contentToScreen]/[screenToContent]).
 *
 * Keyed by [NodeId.value] and held as snapshot state so the selection outline redraws when a node's
 * bounds change (scroll, resize, recomposition — FEATURES C4). Entries are never explicitly evicted:
 * the IR tree is the authority for what exists (a stale entry for a deleted node is simply never
 * queried), so the index only ever answers "where is this node that the tree says exists?".
 */
class NodeBounds {
    private val rects = mutableStateMapOf<String, Rect>()

    /** Record (content-space) bounds for a node; a no-op if unchanged, to avoid a recomposition loop. */
    fun record(id: NodeId, rect: Rect) {
        if (rects[id.value] != rect) rects[id.value] = rect
    }

    fun boundsOf(id: NodeId): Rect? = rects[id.value]

    /** A plain snapshot for pure hit-testing off the composition thread. */
    fun snapshot(): Map<String, Rect> = rects.toMap()
}

/**
 * The deepest node in [root]'s subtree whose recorded bounds contain [point] (content space), or null
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
 * Every node under [root] whose recorded bounds are *fully enclosed* by [marquee] (content space), for
 * rubber-band selection (C10, #93). The result is *top-level*: once an enclosed node is taken its
 * subtree is skipped, so a swept container is selected without also selecting each of its children
 * (matching the `selectionTopLevel` dedup the batch ops use). [root] itself is never returned — a
 * marquee selects content on the frame, not the frame.
 *
 * Full-enclosure (not mere intersection) is the design-tool default: it avoids grabbing a large
 * container from a partial sweep. Hidden subtrees are skipped entirely (not rendered); a locked node
 * (T4) is skipped but its unlocked descendants remain eligible, mirroring click-selection. Pure and
 * Compose-free, so it is unit-tested without a UI harness.
 */
fun marqueeSelection(rects: Map<String, Rect>, root: Node, marquee: Rect): List<NodeId> {
    val out = mutableListOf<NodeId>()
    root.allChildren().forEach { collectMarquee(rects, it, marquee, out) }
    return out
}

private fun collectMarquee(rects: Map<String, Rect>, node: Node, marquee: Rect, out: MutableList<NodeId>) {
    if (node.hidden) return
    val rect = rects[node.id.value]
    if (rect != null && !node.locked && marquee.enclosesRect(rect)) {
        out += node.id // enclosed and selectable: take it and stop — its children would be redundant
        return
    }
    node.allChildren().forEach { collectMarquee(rects, it, marquee, out) }
}

/** True if this rectangle fully contains [inner] (edges may touch). */
private fun Rect.enclosesRect(inner: Rect): Boolean =
    left <= inner.left && top <= inner.top && right >= inner.right && bottom >= inner.bottom

/**
 * Every layout container in [root]'s subtree (including [root] itself when it is one), for the debug
 * "show borders" overlay (#117). A container is any node the catalog reports as holding children in its
 * default region or a named slot ([viewforge.editor.state.ComponentCatalog.isContainer]) — Box, Row,
 * Column, Card, Surface, Scaffold, and so on. Hidden subtrees are skipped: they are not rendered, so they
 * have no recorded bounds to outline. Pure and Compose-free, so it is unit-tested without a UI harness.
 */
fun containerNodes(root: Node, isContainer: (String) -> Boolean): List<Node> {
    val out = mutableListOf<Node>()
    collectContainers(root, isContainer, out)
    return out
}

private fun collectContainers(node: Node, isContainer: (String) -> Boolean, out: MutableList<Node>) {
    if (node.hidden) return
    if (isContainer(node.type)) out += node
    node.allChildren().forEach { collectContainers(it, isContainer, out) }
}

/** Whether a [MeasureSegment] runs horizontally (a left/right gap) or vertically (a top/bottom gap). */
enum class MeasureAxis { Horizontal, Vertical }

/** One gap of the measure overlay (C12, #119): a line from [start] to [end] labelled with [distance] px. */
data class MeasureSegment(val start: Offset, val end: Offset, val distance: Float, val axis: MeasureAxis)

/**
 * The spacing gaps from the node [selectedId] to its parent container's four inner edges (C12, #119),
 * for the measure overlay — the distance from each edge of the node to the corresponding edge of the
 * container that holds it. Content space, so the result stays correct at any zoom/pan once the overlay
 * maps it through [contentToScreen]. Each gap is centred on the node along the perpendicular axis.
 *
 * Empty when the node has no recorded bounds, no parent (it is the root — nothing to measure against), or
 * the parent has no bounds. A side whose gap is negative (the node overflows the container there) is
 * dropped rather than drawn backwards. Pure and Compose-free, so it is unit-tested without a UI harness.
 * v1 measures to the container edges; measuring to the nearest sibling per side is the #127 follow-up.
 */
fun measureGaps(rects: Map<String, Rect>, root: Node, selectedId: NodeId): List<MeasureSegment> {
    val child = rects[selectedId.value] ?: return emptyList()
    val parent = parentContaining(root, selectedId) ?: return emptyList()
    val p = rects[parent.id.value] ?: return emptyList()
    val cx = (child.left + child.right) / 2f
    val cy = (child.top + child.bottom) / 2f
    return listOf(
        MeasureSegment(Offset(p.left, cy), Offset(child.left, cy), child.left - p.left, MeasureAxis.Horizontal),
        MeasureSegment(Offset(child.right, cy), Offset(p.right, cy), p.right - child.right, MeasureAxis.Horizontal),
        MeasureSegment(Offset(cx, p.top), Offset(cx, child.top), child.top - p.top, MeasureAxis.Vertical),
        MeasureSegment(Offset(cx, child.bottom), Offset(cx, p.bottom), p.bottom - child.bottom, MeasureAxis.Vertical),
    ).filter { it.distance >= 0f }
}

/** The node in [root]'s subtree whose direct children/slots contain [id], or null (id is the root/absent). */
private fun parentContaining(root: Node, id: NodeId): Node? {
    root.allChildren().forEach { child ->
        if (child.id == id) return root
        parentContaining(child, id)?.let { return it }
    }
    return null
}

/** Whether an [AlignmentGuide] runs vertically (a shared x) or horizontally (a shared y). */
enum class GuideOrientation { Vertical, Horizontal }

/**
 * One static alignment guide (C11, #118): a line at [position] on its cross axis, spanning [start]..[end]
 * along the other axis. A [GuideOrientation.Vertical] guide draws from (position, start) to (position, end);
 * a [GuideOrientation.Horizontal] guide from (start, position) to (end, position).
 */
data class AlignmentGuide(val orientation: GuideOrientation, val position: Float, val start: Float, val end: Float)

/**
 * Static alignment guides for the node [selectedId] (C11, #118): a guide wherever one of the node's three
 * x-lines (left / centre / right) lines up (within [tolerance]) with an x-line of its parent or a sibling,
 * and likewise for its y-lines (top / middle / bottom). Each guide sits at the *selected* node's line and
 * spans the union of the selected node and every neighbour it aligns with there, so it reads as "this edge
 * lines up with those". Content space, so it stays correct at any zoom/pan once mapped through
 * [contentToScreen]. Empty when the node has no recorded bounds or no parent (the root aligns to nothing).
 * Pure and Compose-free, unit-tested without a UI harness. (Drag-time snapping is the blocked #129.)
 */
fun alignmentGuides(
    rects: Map<String, Rect>,
    root: Node,
    selectedId: NodeId,
    tolerance: Float = GUIDE_TOLERANCE,
): List<AlignmentGuide> {
    val sel = rects[selectedId.value] ?: return emptyList()
    val parent = parentContaining(root, selectedId) ?: return emptyList()
    val neighbours =
        (listOf(parent) + parent.children.filter { it.id != selectedId }).mapNotNull { rects[it.id.value] }
    val selXs = listOf(sel.left, (sel.left + sel.right) / 2f, sel.right)
    val selYs = listOf(sel.top, (sel.top + sel.bottom) / 2f, sel.bottom)
    // The selected node's line (only three distinct x's / y's) keys the guide; the value is its running
    // span along the perpendicular axis, widened to cover every neighbour that aligns on that line.
    val vertical = mutableMapOf<Float, Pair<Float, Float>>()
    val horizontal = mutableMapOf<Float, Pair<Float, Float>>()
    for (n in neighbours) {
        val nXs = listOf(n.left, (n.left + n.right) / 2f, n.right)
        val nYs = listOf(n.top, (n.top + n.bottom) / 2f, n.bottom)
        selXs.filter { sx -> nXs.any { abs(sx - it) <= tolerance } }
            .forEach { sx -> mergeSpan(vertical, sx, minOf(sel.top, n.top), maxOf(sel.bottom, n.bottom)) }
        selYs.filter { sy -> nYs.any { abs(sy - it) <= tolerance } }
            .forEach { sy -> mergeSpan(horizontal, sy, minOf(sel.left, n.left), maxOf(sel.right, n.right)) }
    }
    return vertical.map { AlignmentGuide(GuideOrientation.Vertical, it.key, it.value.first, it.value.second) } +
        horizontal.map { AlignmentGuide(GuideOrientation.Horizontal, it.key, it.value.first, it.value.second) }
}

private fun mergeSpan(map: MutableMap<Float, Pair<Float, Float>>, key: Float, start: Float, end: Float) {
    val prev = map[key]
    map[key] = if (prev == null) start to end else minOf(prev.first, start) to maxOf(prev.second, end)
}

/**
 * The one canonical content↔screen transform (TECHNICAL_NOTES §5, #116): all coordinate math goes
 * through here so zoom/pan is applied in exactly one place. Node bounds are stored in the frame's
 * unscaled *content* space; the overlay draws and hit-tests in its own (screen) local space. The frame
 * is centre-anchored in the overlay and its `graphicsLayer` scales about that centre, so a content point
 * `c` maps to a screen point `s = center + zoom·(c − frameHalf) + pan`, where `center` is the overlay
 * centre and `frameHalf` is half the device frame's pixel size. Pure (floats only) so the mapping is
 * unit-tested without a composition.
 */
internal fun contentToScreen(point: Offset, overlaySize: Size, framePx: Size, viewport: CanvasViewport): Offset {
    val center = Offset(overlaySize.width / 2f, overlaySize.height / 2f)
    val half = Offset(framePx.width / 2f, framePx.height / 2f)
    return center + (point - half) * viewport.zoom + Offset(viewport.panX, viewport.panY)
}

/** The inverse of [contentToScreen]: a screen (overlay-local) point back to unscaled content space. */
internal fun screenToContent(point: Offset, overlaySize: Size, framePx: Size, viewport: CanvasViewport): Offset {
    val center = Offset(overlaySize.width / 2f, overlaySize.height / 2f)
    val half = Offset(framePx.width / 2f, framePx.height / 2f)
    return (point - center - Offset(viewport.panX, viewport.panY)) / viewport.zoom + half
}

/** A content-space [rect] mapped to screen: the top-left through [contentToScreen], the size scaled by zoom. */
internal fun contentRectToScreen(rect: Rect, overlaySize: Size, framePx: Size, viewport: CanvasViewport): Rect {
    val topLeft = contentToScreen(rect.topLeft, overlaySize, framePx, viewport)
    return Rect(topLeft, rect.size * viewport.zoom)
}

/**
 * Transient state for a canvas drag-to-reparent gesture (C7), the geometric-drop counterpart of the
 * tree panel's `TreeDragState`. It holds the dragged node and the resolved drop, recomputed on each
 * drag move from the content-space bounds and the pure [canvasDropAddress]; validity is confirmed by
 * [EditorState.canDrop] and the move committed via [EditorState.moveNode], so it shares the exact drop
 * rules and post-removal index semantics the tree uses. All positions are content space — the pointer is
 * mapped through [screenToContent] before it reaches here — so the resolution stays correct at every zoom/pan.
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

    /** The insertion-caret endpoints (content space), or null for an into-empty-container drop (outline only). */
    var caret by mutableStateOf<Pair<Offset, Offset>?>(null)
        private set

    private var dropAddress: ChildAddress? = null

    /** Begin dragging [id] (the deepest hit under the press); callers must exclude the root and locked nodes. */
    fun begin(id: NodeId) {
        draggingId = id
    }

    /** Resolve the drop for a content-space [point] and update the feedback; a no-op if not dragging. */
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
 * Transient state for a canvas marquee / rubber-band selection (C10, #93): a press-drag on empty canvas
 * (or the frame background) sweeps a rectangle and selects every node fully enclosed by it on release.
 * It coexists with the other canvas drags by *what the press lands on* — space-pan (C5) and node
 * drag-to-reparent (C7) claim a held space or a press over a node, leaving the empty-canvas press to the
 * marquee. Both endpoints are content space — the pointer is mapped through [screenToContent] first — so the
 * [rect] and the enclosed-node resolution stay correct at every zoom/pan level.
 */
internal class MarqueeState(private val state: EditorState, private val bounds: NodeBounds) {
    private var start by mutableStateOf<Offset?>(null)
    private var current by mutableStateOf<Offset?>(null)
    private var additive = false

    /** Whether a marquee drag is in progress. */
    val active: Boolean get() = start != null

    /** The normalized marquee rectangle in content space, or null when not dragging. */
    val rect: Rect?
        get() {
            val a = start ?: return null
            val b = current ?: return null
            return Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
        }

    /**
     * Begin a sweep at [point]. [additive] (a modifier held at press, #99) adds the enclosed nodes to the
     * current selection rather than replacing it, matching the canvas click convention where Shift/Ctrl/Cmd
     * extend a multi-selection.
     */
    fun begin(point: Offset, additive: Boolean) {
        start = point
        current = point
        this.additive = additive
    }

    fun update(point: Offset) {
        if (start != null) current = point
    }

    /**
     * Resolve the enclosed nodes and apply them via [combineMarquee], then reset. A plain sweep replaces
     * (an empty one clears); an additive sweep unions into the standing selection (an empty one leaves it).
     */
    fun commit() {
        val root = state.activeEditRoot
        val box = rect
        if (root != null && box != null) {
            val hits = marqueeSelection(bounds.snapshot(), root, box)
            state.setSelection(combineMarquee(state.selectedIds, hits, additive))
        }
        reset()
    }

    fun reset() {
        start = null
        current = null
        additive = false
    }
}

/**
 * Merge a marquee's enclosed [hits] with the [base] selection (C10, #99). A non-[additive] sweep replaces
 * outright; an additive sweep unions the two, dropping any base entry that reappears in [hits] so the last
 * hit stays the primary (last) and no node is listed twice.
 */
internal fun combineMarquee(base: List<NodeId>, hits: List<NodeId>, additive: Boolean): List<NodeId> =
    if (additive) base.filter { it !in hits } + hits else hits

/**
 * The insertion caret for [address] in content space: a line across the target at the gap the index
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
 * Because it sits *above* the render output and never draws into it, editor chrome can never affect the
 * user UI's layout.
 *
 * The overlay sits **outside** the content's zoom/pan `graphicsLayer`, so it keeps a constant outline
 * thickness. Node bounds are stored in the frame's unscaled *content* space (#116); the overlay applies
 * the live viewport transform ([contentToScreen]/[screenToContent]) when it draws and when it maps a
 * pointer position, so every outline and hit-test stays aligned at any zoom/pan. The only place that
 * still needs the overlay's own [LayoutCoordinates] is the palette drag, whose pointer arrives in window
 * space and is converted window → overlay-local → content.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SelectionOverlay(state: EditorState, root: Node, bounds: NodeBounds, modifier: Modifier = Modifier) {
    // The overlay's own layout coordinates, used only to bring the palette drag's window-space pointer
    // into overlay-local space before the viewport transform maps it to content space.
    var overlayCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var hovered by remember { mutableStateOf<NodeId?>(null) }
    // Live keyboard-modifier state, read at tap time to tell a plain click (replace selection) from a
    // ctrl/cmd- or shift-click (toggle into a multi-selection, C10).
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    // Measures the short type-name tags drawn on the debug container-border overlay (#117); cached
    // internally by the measurer, so re-drawing every frame while the mode is on stays cheap.
    val textMeasurer = rememberTextMeasurer()
    // The last tapped node and when — for manual double-tap detection that keeps single-tap selection
    // instant (unlike detectTapGestures' onDoubleTap, which delays every tap). Reset per edit surface.
    var lastTap by remember(root) { mutableStateOf<Pair<NodeId, Long>?>(null) }
    val drag = remember(root) { CanvasDragState(state, bounds) }
    val marquee = remember(root) { MarqueeState(state, bounds) }

    // Palette drag-to-canvas (P2a): while a palette drag is live, resolve its drop against the same
    // canvas geometry as a node drag, using the pointer position the palette publishes in window space
    // (converted here to content space). There is no dragged node to exclude (it doesn't exist yet),
    // hence the null id. The resolved address is published back so the palette commits it as an AddNode.
    val dragType = state.paletteDragType
    val dragX = state.paletteDragX
    val dragY = state.paletteDragY
    var paletteAddress: ChildAddress? = null
    var paletteFeedback: PaletteDropFeedback? = null
    val coords = overlayCoords
    if (dragType != null && dragX != null && dragY != null && coords != null) {
        val framePx = with(density) { state.activeDeviceProfile.let { Size(it.width.dp.toPx(), it.height.dp.toPx()) } }
        val point =
            screenToContent(coords.windowToLocal(Offset(dragX, dragY)), coords.size.toSize(), framePx, state.viewport)
        val rects = bounds.snapshot()
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
            .onGloballyPositioned { overlayCoords = it }
            .pointerInput(root) {
                val scope = this
                val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
                detectTapGestures { local ->
                    // While panning (space held) a press is a pan, not a selection — ignore it here.
                    if (state.isSpaceHeld) return@detectTapGestures
                    val hit = hitTest(bounds.snapshot(), root, scope.pointerToContent(local, state))
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
            // Drag on the canvas (C7 reparent / C10 marquee). Inactive while space-panning, so pan stays
            // mutually exclusive; a press with no movement falls through to the tap-to-select above. The
            // press location arbitrates: over a movable node it reparents, on the frame/empty canvas it
            // rubber-band selects (#93). The root fills the frame, so an "empty" press hits root.id, not null.
            .pointerInput(root, state.isSpaceHeld) {
                if (state.isSpaceHeld) return@pointerInput
                val scope = this
                detectDragGestures(
                    onDragStart = { local ->
                        val point = scope.pointerToContent(local, state)
                        val hit = hitTest(bounds.snapshot(), root, point)
                        if (hit == null || hit == root.id) {
                            // Frame/empty canvas → marquee-select. A modifier held at press adds to the
                            // selection instead of replacing (#99), matching the canvas click convention.
                            val mods = windowInfo.keyboardModifiers
                            marquee.begin(point, mods.isCtrlPressed || mods.isMetaPressed || mods.isShiftPressed)
                        } else if (root.findById(hit)?.locked != true) {
                            drag.begin(hit) // over a movable node → reparent; locked nodes don't drag
                        }
                    },
                    onDrag = { change, _ ->
                        val point = scope.pointerToContent(change.position, state)
                        when {
                            marquee.active -> {
                                change.consume()
                                marquee.update(point)
                            }
                            drag.draggingId != null -> {
                                change.consume()
                                drag.update(point)
                            }
                        }
                    },
                    onDragEnd = { if (marquee.active) marquee.commit() else drag.commit() },
                    onDragCancel = { if (marquee.active) marquee.reset() else drag.reset() },
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
                val scope = this
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
                                hovered = hitTest(bounds.snapshot(), root, scope.pointerToContent(local, state))
                            }
                        }
                    }
                }
            },
    ) {
        // The live viewport transform: content-space bounds → this (unscaled) overlay's screen space.
        val viewport = state.viewport
        val framePx = state.activeDeviceProfile.let { Size(it.width.dp.toPx(), it.height.dp.toPx()) }
        val overlaySize = size
        val rectToScreen = { rect: Rect -> contentRectToScreen(rect, overlaySize, framePx, viewport) }
        val pointToScreen = { p: Offset -> contentToScreen(p, overlaySize, framePx, viewport) }

        // Debug container-border overlay (#117): a distinct dashed outline + type tag around every layout
        // container. Drawn first — beneath the selection/hover/drop feedback and before any early-return —
        // so it reads as a separate structural layer and stays visible even mid-drag or mid-marquee.
        if (state.showBorders) {
            containerNodes(root, state.catalog::isContainer).forEach { node ->
                bounds.boundsOf(node.id)?.let { content ->
                    val screen = rectToScreen(content)
                    drawOutline(screen, BORDER_COLOR, BORDER_STROKE, BORDER_DASH)
                    drawContainerLabel(textMeasurer, node.type.substringAfterLast('.'), screen.topLeft)
                }
            }
        }

        // Palette drag wins the overlay while it's live: show its drop feedback and nothing else.
        paletteFeedback?.let { fb ->
            val color = if (fb.valid) DROP_OK else DROP_BAD
            fb.outlineId?.let { id ->
                bounds.boundsOf(id)?.let { drawOutline(rectToScreen(it), color, DROP_STROKE) }
            }
            fb.caret?.let { (a, b) -> drawLine(color, pointToScreen(a), pointToScreen(b), strokeWidth = DROP_STROKE) }
            return@Canvas
        }
        if (drag.draggingId != null) {
            // Mid-drag: show only drop feedback (green = legal, red = rejected) so it isn't lost among
            // the hover/selection outlines. The caret marks the exact insertion gap.
            val color = if (drag.dropValid) DROP_OK else DROP_BAD
            drag.outlineId?.let { id ->
                bounds.boundsOf(id)?.let { drawOutline(rectToScreen(it), color, DROP_STROKE) }
            }
            drag.caret?.let { (a, b) -> drawLine(color, pointToScreen(a), pointToScreen(b), strokeWidth = DROP_STROKE) }
            return@Canvas
        }
        // Mid-marquee: draw only the rubber-band rectangle (a translucent fill + thin outline) so it isn't
        // lost among hover/selection outlines; the enclosed nodes are resolved and selected on release.
        marquee.rect?.let { box ->
            val screen = rectToScreen(box)
            drawRect(MARQUEE_FILL, topLeft = screen.topLeft, size = screen.size)
            drawOutline(screen, MARQUEE_STROKE_COLOR, MARQUEE_STROKE)
            return@Canvas
        }
        // Hover first, selection on top: when a node is both, the selection outline wins visually.
        hovered?.takeIf { !state.isSelected(it) }?.let { id ->
            bounds.boundsOf(id)?.let { drawOutline(rectToScreen(it), HOVER_COLOR, HOVER_STROKE) }
        }
        // Every selected node is outlined (C10); the secondary selections draw faded, the primary solid
        // and on top so it reads as the focused node.
        val primary = state.selectedId
        state.selectedIds.forEach { id ->
            if (id == primary) return@forEach
            bounds.boundsOf(id)?.let { drawOutline(rectToScreen(it), MULTI_SELECTION_COLOR, SELECTION_STROKE) }
        }
        primary?.let { id ->
            bounds.boundsOf(id)?.let { drawOutline(rectToScreen(it), SELECTION_COLOR, SELECTION_STROKE) }
        }
        // Static alignment guides (C11, #118): while the mode is on, draw a line wherever the primary
        // selection's edges/centre line up with a sibling or the parent. Reuses the content-space transform.
        if (state.showGuides) {
            primary?.let { id ->
                alignmentGuides(bounds.snapshot(), root, id).forEach { g ->
                    val (a, b) =
                        if (g.orientation == GuideOrientation.Vertical) {
                            Offset(g.position, g.start) to Offset(g.position, g.end)
                        } else {
                            Offset(g.start, g.position) to Offset(g.end, g.position)
                        }
                    drawLine(GUIDE_COLOR, pointToScreen(a), pointToScreen(b), strokeWidth = GUIDE_STROKE)
                }
            }
        }
        // Measure/spacing overlay (C12, #119): while the measure key is held, annotate the gaps from the
        // primary selection to its parent container's edges. Drawn last, above the selection outline. The
        // distance is converted from content px to dp (a DrawScope is a Density) for the label.
        if (state.isMeasuring) {
            primary?.let { id ->
                measureGaps(bounds.snapshot(), root, id).forEach { seg ->
                    val a = pointToScreen(seg.start)
                    val b = pointToScreen(seg.end)
                    drawLine(MEASURE_COLOR, a, b, strokeWidth = MEASURE_STROKE)
                    drawMeasureTicks(a, b, seg.axis)
                    drawMeasureLabel(textMeasurer, seg.distance.toDp().value.roundToInt().toString(), (a + b) / 2f)
                }
            }
        }
    }
}

/**
 * Map a pointer position in this overlay's local (screen) space to the frame's unscaled content space,
 * applying the live viewport transform (#116). Read at event time so it reflects the current zoom/pan
 * and device profile, since the gesture recognizers are not re-armed when those change.
 */
private fun PointerInputScope.pointerToContent(local: Offset, state: EditorState): Offset {
    val framePx = state.activeDeviceProfile.let { Size(it.width.dp.toPx(), it.height.dp.toPx()) }
    return screenToContent(local, size.toSize(), framePx, state.viewport)
}

private fun DrawScope.drawOutline(rect: Rect, color: Color, width: Float, dash: PathEffect? = null) {
    drawRect(
        color = color,
        topLeft = rect.topLeft,
        size = rect.size,
        style = Stroke(width = width, pathEffect = dash),
    )
}

/** Short perpendicular ticks at each end of a measure segment (#119), so the measured span reads clearly. */
private fun DrawScope.drawMeasureTicks(a: Offset, b: Offset, axis: MeasureAxis) {
    val half = MEASURE_TICK / 2f
    if (axis == MeasureAxis.Horizontal) {
        drawLine(MEASURE_COLOR, Offset(a.x, a.y - half), Offset(a.x, a.y + half), strokeWidth = MEASURE_STROKE)
        drawLine(MEASURE_COLOR, Offset(b.x, b.y - half), Offset(b.x, b.y + half), strokeWidth = MEASURE_STROKE)
    } else {
        drawLine(MEASURE_COLOR, Offset(a.x - half, a.y), Offset(a.x + half, a.y), strokeWidth = MEASURE_STROKE)
        drawLine(MEASURE_COLOR, Offset(b.x - half, b.y), Offset(b.x + half, b.y), strokeWidth = MEASURE_STROKE)
    }
}

/** A distance label (dp) centred on a measure segment's midpoint, on a chip for legibility (#119). */
private fun DrawScope.drawMeasureLabel(measurer: TextMeasurer, text: String, at: Offset) {
    val layout = measurer.measure(text, MEASURE_LABEL_STYLE)
    val topLeft =
        at - Offset(layout.size.width / 2f + MEASURE_LABEL_PADDING, layout.size.height / 2f + MEASURE_LABEL_PADDING)
    drawRect(
        color = MEASURE_COLOR,
        topLeft = topLeft,
        size = Size(layout.size.width + MEASURE_LABEL_PADDING * 2, layout.size.height + MEASURE_LABEL_PADDING * 2),
    )
    drawText(layout, topLeft = topLeft + Offset(MEASURE_LABEL_PADDING, MEASURE_LABEL_PADDING))
}

/** A small type-name tag at a container's top-left corner for the debug border overlay (#117). */
private fun DrawScope.drawContainerLabel(measurer: TextMeasurer, text: String, at: Offset) {
    val layout = measurer.measure(text, BORDER_LABEL_STYLE)
    drawRect(
        color = BORDER_COLOR,
        topLeft = at,
        size = Size(layout.size.width + BORDER_LABEL_PADDING * 2, layout.size.height + BORDER_LABEL_PADDING * 2),
    )
    drawText(layout, topLeft = at + Offset(BORDER_LABEL_PADDING, BORDER_LABEL_PADDING))
}

private val SELECTION_COLOR = Color(0xFF1E88E5)

// Faded blue for the non-primary members of a multi-selection (C10), so the solid primary stands out.
private val MULTI_SELECTION_COLOR = Color(0x991E88E5)
private val HOVER_COLOR = Color(0x881E88E5)
private const val SELECTION_STROKE = 2f // px; a thin editor outline, deliberately not layout-affecting
private const val HOVER_STROKE = 1f

// Marquee / rubber-band feedback (C10, #93): a faint blue wash with a thin solid outline.
private val MARQUEE_FILL = Color(0x221E88E5)
private val MARQUEE_STROKE_COLOR = Color(0xFF1E88E5)
private const val MARQUEE_STROKE = 1f

// Drop feedback (C7), matching the tree panel's palette: green = legal drop, red = rejected.
private val DROP_OK = Color(0xFF43A047)
private val DROP_BAD = Color(0xFFB00020)
private const val DROP_STROKE = 2f

// Debug container-border overlay (#117): a dashed purple outline + type tag, deliberately unlike the
// solid blue selection/hover, the green/red drop, and the blue marquee, so structure reads as its own layer.
private val BORDER_COLOR = Color(0xFFAB47BC)
private const val BORDER_STROKE = 1f

// `PathEffect` is skia-backed, so it must not be built at file (class) init — that would drag Skiko's
// native lib into pure, headless unit tests that only load the top-level helpers here (e.g. `hitTest`,
// `containerNodes`). `by lazy` defers it to the first actual draw, on the UI thread with Skiko present.
private val BORDER_DASH by lazy { PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) }
private val BORDER_LABEL_STYLE = TextStyle(color = Color.White, fontSize = 10.sp)
private const val BORDER_LABEL_PADDING = 2f

// Measure/spacing overlay (C12, #119): a distinct orange, apart from the blue selection/hover, the purple
// debug borders (#117), and the green/red drop feedback, so the annotations read as their own layer.
private val MEASURE_COLOR = Color(0xFFF57C00)
private const val MEASURE_STROKE = 1f
private const val MEASURE_TICK = 8f
private val MEASURE_LABEL_STYLE = TextStyle(color = Color.White, fontSize = 10.sp)
private const val MEASURE_LABEL_PADDING = 2f

// Static alignment guides (C11, #118): a distinct pink, apart from the blue selection/hover, purple
// #117 borders, and orange #119 measure lines. Tolerance is content px — the slack for "lines up".
private val GUIDE_COLOR = Color(0xFFFF4081)
private const val GUIDE_STROKE = 1f
private const val GUIDE_TOLERANCE = 1f

/** The overlay's drop feedback for a live palette drag (P2a): a caret at the gap plus a target outline. */
private data class PaletteDropFeedback(val outlineId: NodeId?, val caret: Pair<Offset, Offset>?, val valid: Boolean)

/** Per-scroll-notch zoom multiplier; finer than the menu/keyboard step so the wheel feels continuous. */
private const val ZOOM_IN_FACTOR = 1.1f

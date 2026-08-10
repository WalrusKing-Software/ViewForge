package viewforge.editor.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import viewforge.editor.state.EditorState
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.allChildren

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
 * The transparent editor chrome layer above the rendered UI (ARCHITECTURE §4.4). It owns all pointer
 * input for editing — click to select the deepest node, hover to preview — and draws selection and
 * hover outlines. Because it sits *above* the render output and never draws into it, editor chrome can
 * never affect the user UI's layout.
 */
@Composable
internal fun SelectionOverlay(state: EditorState, root: Node, bounds: NodeBounds, modifier: Modifier = Modifier) {
    var transform by remember { mutableStateOf<CanvasTransform?>(null) }
    var hovered by remember { mutableStateOf<NodeId?>(null) }

    Canvas(
        modifier
            .onGloballyPositioned { transform = CanvasTransform(it) }
            .pointerInput(root) {
                detectTapGestures { local ->
                    val point = transform?.localToWindow(local) ?: local
                    state.select(hitTest(bounds.snapshot(), root, point))
                }
            }
            .pointerInput(root) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        hovered =
                            if (event.type == PointerEventType.Exit) {
                                null
                            } else {
                                val local = event.changes.first().position
                                val point = transform?.localToWindow(local) ?: local
                                hitTest(bounds.snapshot(), root, point)
                            }
                    }
                }
            },
    ) {
        val t = transform ?: return@Canvas
        // Hover first, selection on top: when a node is both, the selection outline wins visually.
        hovered?.takeIf { it != state.selectedId }?.let { id ->
            bounds.boundsOf(id)?.let { drawOutline(t.rectToLocal(it), HOVER_COLOR, HOVER_STROKE) }
        }
        state.selectedId?.let { id ->
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
private val HOVER_COLOR = Color(0x881E88E5)
private const val SELECTION_STROKE = 2f // px; a thin editor outline, deliberately not layout-affecting
private const val HOVER_STROKE = 1f

package viewforge.editor.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import viewforge.editor.state.EditorState
import viewforge.model.Node
import viewforge.model.NodeId

/**
 * The seam between the editor and whatever framework package renders the IR. Compose-typed (so it
 * can't live in the Compose-free `core/spi`), it lets the editor drive rendering without ever
 * naming the Compose package: `:app` supplies the implementation (ARCHITECTURE §3, §6.2).
 *
 * [instrument] is the editor-instrumentation hook (ADR-009): the renderer appends its returned
 * [Modifier] to each node, letting the editor capture per-node bounds for hit-testing. The editor
 * owns *what* the instrumentation does; the renderer only agrees to apply it.
 */
fun interface CanvasRenderer {
    @Composable
    fun Render(root: Node, instrument: (NodeId) -> Modifier)
}

/**
 * The canvas surface (ARCHITECTURE §4). It hosts a single framed viewport, renders the active
 * screen's root through [renderer] with per-node bounds instrumentation, and lays a transparent
 * [SelectionOverlay] on top for click-to-select and hover/selection outlines (M3).
 *
 * Zoom & pan (C5) are realised as a **single `graphicsLayer`** on the rendered frame, driven by
 * [EditorState.viewport]. That is the one canonical transform (TECHNICAL_NOTES §5): because
 * `graphicsLayer` participates in Compose's layout-coordinate chain, the node bounds captured via
 * `boundsInWindow` come back already scaled/panned, and the overlay reconciles pointer events against
 * them in window space — so hit-testing stays correct at every zoom/pan level with no coordinate math
 * at the call sites. The overlay itself is left **unscaled** on top: it is editor chrome, so its
 * outlines keep a constant thickness rather than growing with the zoom.
 *
 * The frame has bounded size so a root that asks to `fillMaxSize` fills the viewport rather than
 * collapsing. The viewport `clipToBounds` so a zoomed-in or panned frame can't bleed over the
 * neighbouring panels.
 */
@Composable
fun EditorCanvas(state: EditorState, renderer: CanvasRenderer, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(CANVAS_BACKDROP).clipToBounds().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val screen = state.activeScreen
        if (screen == null) {
            EmptyCanvasHint()
        } else {
            val bounds = remember { NodeBounds() }
            val viewport = state.viewport
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = viewport.zoom
                        scaleY = viewport.zoom
                        translationX = viewport.panX
                        translationY = viewport.panY
                    }
                    .background(Color.White),
            ) {
                renderer.Render(screen.root) { id ->
                    Modifier.onGloballyPositioned { bounds.record(id, it.boundsInWindow()) }
                }
            }
            SelectionOverlay(state, screen.root, bounds, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun EmptyCanvasHint() {
    BasicText(text = "No screen to display.", style = TextStyle(color = Color(0xFF9E9E9E)))
}

private val CANVAS_BACKDROP = Color(0xFF2B2B2B)

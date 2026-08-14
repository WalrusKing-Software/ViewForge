package viewforge.editor.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import viewforge.editor.state.CanvasFitBounds
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
 *
 * [interactive] is the C13 preview mode (#120): when true the renderer gives its stateful inputs real
 * local state and live callbacks, so buttons, fields, and toggles respond to the pointer instead of being
 * inert. The editor pairs it with removing the selection overlay, so pointer events reach the live tree.
 */
fun interface CanvasRenderer {
    @Composable
    fun Render(root: Node, interactive: Boolean, instrument: (NodeId) -> Modifier)
}

/**
 * The canvas surface (ARCHITECTURE §4). It hosts a single framed viewport, renders the active
 * screen's root through [renderer] with per-node bounds instrumentation, and lays a transparent
 * [SelectionOverlay] on top for click-to-select and hover/selection outlines (M3).
 *
 * Zoom & pan (C5) are realised as a **single `graphicsLayer`** on the rendered frame, driven by
 * [EditorState.viewport]. That is the one canonical transform (TECHNICAL_NOTES §5). Node bounds are
 * captured in the frame's **unscaled content space** (relative to a reference box *below* the layer, via
 * `localBoundingBoxOf`), so they are invariant to zoom/pan — the `graphicsLayer` is a draw-time transform
 * that does not re-run layout, so bounds captured in window space would silently go stale on zoom (#116).
 * The [SelectionOverlay] applies the live viewport transform when it draws and hit-tests, keeping outlines
 * aligned at every zoom/pan level. The overlay itself is left **unscaled** on top, so its outlines keep a
 * constant stroke thickness rather than growing with the zoom.
 *
 * The frame has bounded size so a root that asks to `fillMaxSize` fills the viewport rather than
 * collapsing. The viewport `clipToBounds` so a zoomed-in or panned frame can't bleed over the
 * neighbouring panels.
 */
@Composable
fun EditorCanvas(state: EditorState, renderer: CanvasRenderer, modifier: Modifier = Modifier) {
    // `BoxWithConstraints` (inside the padding) hands us the available content area in pixels, which the
    // auto-fit (C6, #59) needs to compute the zoom that shows the whole device frame.
    Box(modifier.fillMaxSize().background(CANVAS_BACKDROP).clipToBounds()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            // The edit surface is the active screen's root, or an opened component's root (#61).
            val editRoot = state.activeEditRoot
            if (editRoot == null) {
                EmptyCanvasHint()
            } else {
                val bounds = remember { NodeBounds() }
                val viewport = state.viewport
                // The frame is sized to the screen's device profile (C6) rather than filling the viewport, so
                // the canvas clips to a real device size and a `fillMaxSize` root fills the *device*. The C5
                // zoom/pan graphicsLayer wraps it, so a frame larger than the viewport stays navigable.
                val profile = state.activeDeviceProfile
                val density = LocalDensity.current.density
                val availW = constraints.maxWidth.toFloat()
                val availH = constraints.maxHeight.toFloat()
                // Record the measured area so the on-demand Fit (View menu / Ctrl+9) has a size to fit to.
                LaunchedEffect(availW, availH, density) {
                    state.canvasFitBounds = CanvasFitBounds(availW, availH, density)
                }
                // Auto-fit when the profile changes (and on first show) so a frame larger than the viewport
                // is visible without a manual zoom-out. Keyed on the profile alone, not the size, so a window
                // resize doesn't overwrite a zoom the user set by hand.
                LaunchedEffect(profile.id) {
                    if (availW > 0f && availH > 0f) state.fitToFrame(availW, availH, density)
                }
                // The unscaled content reference (below the graphicsLayer) node bounds are captured against,
                // so they are zoom/pan-invariant; the overlay applies the live transform (#116).
                var contentCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                Box(
                    Modifier
                        .size(profile.width.dp, profile.height.dp)
                        .graphicsLayer {
                            scaleX = viewport.zoom
                            scaleY = viewport.zoom
                            translationX = viewport.panX
                            translationY = viewport.panY
                        }
                        .background(Color.White),
                ) {
                    Box(Modifier.matchParentSize().onGloballyPositioned { contentCoords = it }) {
                        renderer.Render(editRoot, state.interactivePreview) { id ->
                            Modifier.onGloballyPositioned { child ->
                                contentCoords?.let {
                                    bounds.record(id, it.localBoundingBoxOf(child, clipBounds = false))
                                }
                            }
                        }
                    }
                }
                // In interactive preview (C13, #120) the selection overlay is removed so pointer events reach
                // the live tree — clicks, typing, and scrolling hit the real components rather than being
                // captured for selection/hover/drag. Selection state is untouched, so leaving preview restores
                // the outlines.
                if (!state.interactivePreview) SelectionOverlay(state, editRoot, bounds, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun EmptyCanvasHint() {
    BasicText(text = "No screen to display.", style = TextStyle(color = Color(0xFF9E9E9E)))
}

private val CANVAS_BACKDROP = Color(0xFF2B2B2B)

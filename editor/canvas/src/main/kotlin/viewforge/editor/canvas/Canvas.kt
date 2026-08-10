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
import androidx.compose.ui.graphics.Color
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
 * [SelectionOverlay] on top for click-to-select and hover/selection outlines (M3). Zoom and pan are
 * a later milestone; [CanvasTransform] is the single seam they will hook (TECHNICAL_NOTES §5).
 *
 * The frame has bounded size so a root that asks to `fillMaxSize` fills the viewport rather than
 * collapsing.
 */
@Composable
fun EditorCanvas(state: EditorState, renderer: CanvasRenderer, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(CANVAS_BACKDROP).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val screen = state.activeScreen
        if (screen == null) {
            EmptyCanvasHint()
        } else {
            val bounds = remember { NodeBounds() }
            Box(Modifier.fillMaxSize().background(Color.White)) {
                renderer.Render(screen.root) { id ->
                    Modifier.onGloballyPositioned { bounds.record(id, it.boundsInWindow()) }
                }
                SelectionOverlay(state, screen.root, bounds, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun EmptyCanvasHint() {
    BasicText(text = "No screen to display.", style = TextStyle(color = Color(0xFF9E9E9E)))
}

private val CANVAS_BACKDROP = Color(0xFF2B2B2B)

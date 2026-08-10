package viewforge.editor.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import viewforge.editor.state.EditorState
import viewforge.model.Node

/**
 * The seam between the editor and whatever framework package renders the IR. Compose-typed (so it
 * can't live in the Compose-free `core/spi`), it lets the editor drive rendering without ever
 * naming the Compose package: `:app` supplies the implementation (ARCHITECTURE §3, §6.2).
 */
fun interface CanvasRenderer {
    @Composable
    fun Render(root: Node)
}

/**
 * The canvas surface (ARCHITECTURE §4). For M2 it hosts a single framed viewport and renders the
 * active screen's root through [renderer]; selection, hit-testing, zoom and pan are later milestones
 * (M3+). The frame has bounded size so a root that asks to `fillMaxSize` fills the viewport rather
 * than collapsing.
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
            Box(Modifier.fillMaxSize().background(Color.White)) {
                renderer.Render(screen.root)
            }
        }
    }
}

@Composable
private fun EmptyCanvasHint() {
    BasicText(text = "No screen to display.", style = TextStyle(color = Color(0xFF9E9E9E)))
}

private val CANVAS_BACKDROP = Color(0xFF2B2B2B)

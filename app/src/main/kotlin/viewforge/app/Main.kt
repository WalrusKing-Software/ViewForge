package viewforge.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import viewforge.editor.canvas.CanvasRenderer
import viewforge.editor.shell.EditorShell
import viewforge.editor.state.EditorState
import viewforge.packages.compose.render.ComposeRenderer

/**
 * Desktop entry point and the single bootstrapping site allowed a compile-time dependency on
 * `packages/compose` (ARCHITECTURE §3): it binds the editor's Compose-free [CanvasRenderer] seam to
 * the Compose package's [ComposeRenderer]. Nothing else in the editor names the framework package.
 *
 * M2 loads a hardcoded document ([sampleProject]) and renders it in a real Compose Desktop window;
 * open/save, packaging, and the rest of the shell arrive in later milestones.
 */
fun main() = application {
    val state = EditorState(sampleProject())

    // The wiring: the editor asks CanvasRenderer to draw a node; the Compose package obliges,
    // theming it with the project's own theme. `dark = false` until the canvas gains a mode
    // toggle (FEATURES H2).
    val renderer =
        CanvasRenderer { root ->
            ComposeRenderer.RenderScreen(root = root, theme = state.project.theme, dark = false)
        }

    val windowState = rememberWindowState(size = DpSize(1280.dp, 832.dp))
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "ViewForge",
    ) {
        EditorShell(state, renderer)
    }
}

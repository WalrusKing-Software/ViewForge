package viewforge.packages.compose.render

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import viewforge.model.Node
import viewforge.model.Theme

/**
 * The Compose framework package's public rendering entry point (ARCHITECTURE §6.2, renderer half).
 *
 * The editor never calls this directly — it stays behind the editor's `CanvasRenderer` seam, which
 * `:app` wires to this in the single bootstrapping file allowed to know the Compose package
 * (ARCHITECTURE §3). This is the only place the project's own `MaterialTheme` is established, so the
 * rendered UI is themed by the project, distinct from the editor's chrome (FEATURES S3).
 */
object ComposeRenderer {
    @Composable
    fun RenderScreen(root: Node, theme: Theme, dark: Boolean) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            RenderNode(root, RenderContext(theme = theme, dark = dark))
        }
    }
}

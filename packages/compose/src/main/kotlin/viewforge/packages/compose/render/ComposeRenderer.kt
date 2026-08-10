package viewforge.packages.compose.render

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Theme

/**
 * The Compose framework package's public rendering entry point (ARCHITECTURE §6.2, renderer half).
 *
 * The editor never calls this directly — it stays behind the editor's `CanvasRenderer` seam, which
 * `:app` wires to this in the single bootstrapping file allowed to know the Compose package
 * (ARCHITECTURE §3). This is the only place the project's own `MaterialTheme` is established, so the
 * rendered UI is themed by the project, distinct from the editor's chrome (FEATURES S3).
 *
 * The scheme and shapes come from the *project* theme via [projectColorScheme]/[projectShapes] (M8,
 * H1) — Material defaults with the project's `colors.*`/`shapes.*` tokens overlaid — so theme edits
 * apply live across the canvas, and the canvas matches the generated `AppTheme` wrapper (ADR-018).
 * [dark] selects which half of each light/dark pair to preview (H2).
 */
object ComposeRenderer {
    @Composable
    fun RenderScreen(root: Node, theme: Theme, dark: Boolean, instrument: (NodeId) -> Modifier = { Modifier }) {
        MaterialTheme(
            colorScheme = projectColorScheme(theme, dark),
            shapes = projectShapes(theme),
        ) {
            RenderNode(root, RenderContext(theme = theme, dark = dark, instrument = instrument))
        }
    }
}

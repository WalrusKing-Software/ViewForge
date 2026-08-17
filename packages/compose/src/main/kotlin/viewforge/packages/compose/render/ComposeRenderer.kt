package viewforge.packages.compose.render

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import viewforge.model.ComponentDef
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
    fun RenderScreen(
        root: Node,
        theme: Theme,
        dark: Boolean,
        instrument: (NodeId) -> Modifier = { Modifier },
        imageLoader: (assetId: String) -> ImageBitmap? = { null },
        components: List<ComponentDef> = emptyList(),
        interactive: Boolean = false,
        editorAffordances: Boolean = false,
    ) {
        ProjectTheme(theme, dark) {
            RenderNode(
                root,
                RenderContext(
                    theme = theme,
                    dark = dark,
                    instrument = instrument,
                    imageLoader = imageLoader,
                    components = components.associateBy { it.id },
                    interactive = interactive,
                    editorAffordances = editorAffordances,
                ),
            )
        }
    }

    /**
     * Establishes the project's `MaterialTheme` — the same scheme/shapes [RenderScreen] renders under
     * (H1/H2, ADR-020), so it is the code twin of the generated `AppTheme` wrapper. Exposed so a
     * fidelity check can render a hand-written composable under the *identical* theme context the canvas
     * uses, making an interpreter-vs-compiled pixel comparison fair (M9, exit criterion #3).
     *
     * Content color is pinned to [Color.Black] — the `LocalContentColor` value a compiled screen sees
     * under the generated `AppTheme` (a bare `MaterialTheme` with no `Surface`, so nothing overrides the
     * CompositionLocal default). Without this, canvas text and icons with no explicit color would inherit
     * the *editor chrome's* content color and render faint, diverging from codegen (#155). Nested
     * `Surface` nodes still set their own content color for their children, as they do in generated code.
     */
    @Composable
    fun ProjectTheme(theme: Theme, dark: Boolean, content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = projectColorScheme(theme, dark),
            shapes = projectShapes(theme),
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.Black, content = content)
        }
    }
}

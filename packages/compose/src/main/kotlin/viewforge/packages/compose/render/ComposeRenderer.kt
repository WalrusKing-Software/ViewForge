package viewforge.packages.compose.render

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import viewforge.model.ComponentDef
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.StateField
import viewforge.model.Theme
import viewforge.model.resolvedForBreakpoint

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
        state: List<StateField> = emptyList(),
        interactive: Boolean = false,
        editorAffordances: Boolean = false,
        activeBreakpoint: String? = null,
    ) {
        ProjectTheme(theme, dark) {
            val ctx = RenderContext(
                theme = theme,
                dark = dark,
                instrument = instrument,
                imageLoader = imageLoader,
                components = components.associateBy { it.id },
                interactive = interactive,
                editorAffordances = editorAffordances,
            )
            // Responsive resolution (ADR-030/037, #314): overlay the active breakpoint's per-breakpoint prop
            // overrides before any binding/repeat expansion, so the canvas previews the same values codegen's
            // `BoxWithConstraints` would pick at that width. Override-free trees resolve to the same instance
            // (`resolvedForBreakpoint` returns `this`), so a non-responsive screen is untouched and the
            // structural-sharing remember keys below stay stable.
            val resolved = remember(root, activeBreakpoint) { root.resolvedForBreakpoint(activeBreakpoint) }
            if (interactive) {
                // C13 run mode (ADR-035): back the writable state with an ephemeral store seeded from the
                // samples, re-resolve bindings against the LIVE values each change, and hand each widget a
                // reducer that applies its handler actions to that store. Nothing is persisted to the IR.
                InteractiveScreen(resolved, state, ctx)
            } else {
                // Static design canvas (ADR-034): bindings become sample literals and repeats expand to their
                // rows, so RenderNode only ever sees an ordinary tree. Remembered on (root, state) so it
                // recomputes only when the screen or its data changes, like bindParameters.
                RenderNode(remember(resolved, state) { expandScreenState(resolved, state) }, ctx)
            }
        }
    }

    /**
     * The C13 interactive-preview holder (ADR-035, #277): remembers the ephemeral [InteractiveState] store
     * seeded from [state]'s samples, re-expands [root] against the current live values so bound props redraw,
     * and provides [RenderContext.dispatch] so a widget's event handler mutates the store. Purely ephemeral —
     * keyed on [root] so a structural edit resets it — and no evaluation (the reducer is a `when`, PF-4).
     */
    @Composable
    private fun InteractiveScreen(root: Node, state: List<StateField>, ctx: RenderContext) {
        var live by remember(root) { mutableStateOf(initialInteractiveState(state)) }
        val expanded = remember(root, live) {
            expandScreenState(root, state.map { it.copy(sample = live[it.name] ?: it.sample) })
        }
        RenderNode(expanded, ctx.copy(dispatch = { actions -> live = applyActions(live, actions) }))
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

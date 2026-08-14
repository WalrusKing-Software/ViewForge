package viewforge.packages.compose.render

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import viewforge.model.ComponentDef
import viewforge.model.NodeId
import viewforge.model.Theme

/**
 * What every node needs while it renders (ARCHITECTURE §4.2): the project [theme] for token
 * resolution, the [dark] flag selecting the light/dark half of each theme color pair, the editor's
 * [instrument] hook, and the [imageLoader] seam an `Image` node uses to resolve a `ResourceRef`.
 *
 * [instrument] is the editor-instrumentation seam (ADR-009): the renderer appends whatever [Modifier]
 * it returns to each node's own chain, and the editor uses that to capture per-node bounds for
 * hit-testing. It defaults to a no-op so the render layer stays usable — and unit-testable — without
 * an editor. The Compose package never knows *what* the editor does with it; it only knows a node
 * gets an extra modifier keyed by its id.
 *
 * [imageLoader] maps an asset id to a decoded [ImageBitmap], or null when it can't be resolved (so the
 * canvas draws a loud placeholder rather than a blank — ARCHITECTURE §9). Decoding lives with the
 * caller (`:app` reads it from the project), keeping the render layer free of disk access; it defaults
 * to "no images" so the interpreter stays unit-testable without an asset store.
 *
 * [components] resolves a `vforge.userComponent` instance to its definition so the canvas draws the
 * referenced component (ADR-024) — the render twin of codegen's instance-as-call. [expanding] is the
 * set of component ids currently mid-render: an id already in it means an instance re-entered its own
 * definition, so the canvas draws a loud cycle placeholder rather than recursing forever. Load-time
 * validation forbids cycles (PF-3), but the canvas renders mid-edit before that runs, so the guard is a
 * necessary defence, not a duplicate. Both default to empty so the interpreter stays usable — and
 * unit-testable — for a project with no user components.
 *
 * [interactive] is the C13 preview mode (#120): normally the canvas draws inert components (a `Checkbox`'s
 * `onCheckedChange` is a no-op, a `TextField` reflects its `value` prop but isn't editable) so the drawing
 * mirrors the generated code and pointer events go to the editor's selection overlay. When true, the
 * stateful inputs instead carry their own local `remember` state and live callbacks, so the user can click,
 * type, and toggle to feel the real UI. It defaults false, so codegen and the fidelity tests — which never
 * set it — are untouched; only the editor's live canvas opts in.
 */
data class RenderContext(
    val theme: Theme,
    val dark: Boolean,
    val instrument: (NodeId) -> Modifier = { Modifier },
    val imageLoader: (assetId: String) -> ImageBitmap? = { null },
    val components: Map<String, ComponentDef> = emptyMap(),
    val expanding: Set<String> = emptySet(),
    val interactive: Boolean = false,
)

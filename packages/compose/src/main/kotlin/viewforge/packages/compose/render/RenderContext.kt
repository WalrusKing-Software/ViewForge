package viewforge.packages.compose.render

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
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
 */
data class RenderContext(
    val theme: Theme,
    val dark: Boolean,
    val instrument: (NodeId) -> Modifier = { Modifier },
    val imageLoader: (assetId: String) -> ImageBitmap? = { null },
)

package viewforge.packages.compose.render

import androidx.compose.ui.Modifier
import viewforge.model.NodeId
import viewforge.model.Theme

/**
 * What every node needs while it renders (ARCHITECTURE §4.2): the project [theme] for token
 * resolution, the [dark] flag selecting the light/dark half of each theme color pair, and the
 * editor's [instrument] hook.
 *
 * [instrument] is the editor-instrumentation seam (ADR-009): the renderer appends whatever [Modifier]
 * it returns to each node's own chain, and the editor uses that to capture per-node bounds for
 * hit-testing. It defaults to a no-op so the render layer stays usable — and unit-testable — without
 * an editor. The Compose package never knows *what* the editor does with it; it only knows a node
 * gets an extra modifier keyed by its id.
 */
data class RenderContext(val theme: Theme, val dark: Boolean, val instrument: (NodeId) -> Modifier = { Modifier })

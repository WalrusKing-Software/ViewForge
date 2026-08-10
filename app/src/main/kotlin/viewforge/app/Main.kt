package viewforge.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import viewforge.editor.canvas.CanvasRenderer
import viewforge.editor.shell.EditorShell
import viewforge.editor.state.ComponentCatalog
import viewforge.editor.state.EditorState
import viewforge.editor.state.PaletteEntry
import viewforge.model.Node
import viewforge.packages.compose.catalog.ComposeComponents
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
    val state = EditorState(sampleProject(), ComposeCatalog)

    // The wiring: the editor asks CanvasRenderer to draw a node, handing it the per-node bounds
    // instrumentation the canvas needs for hit-testing (ADR-009); the Compose package obliges,
    // theming it with the project's own theme and applying that instrumentation to each node.
    // `dark = false` until the canvas gains a mode toggle (FEATURES H2).
    val renderer =
        CanvasRenderer { root, instrument ->
            ComposeRenderer.RenderScreen(
                root = root,
                theme = state.document.theme,
                dark = false,
                instrument = instrument,
            )
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

/**
 * Adapts the Compose package's [ComposeComponents] catalog to the editor's Compose-free
 * [ComponentCatalog] seam — the same bootstrapping role `Main` plays for `CanvasRenderer` (ADR-013).
 * The editor consults this for the palette and drop validation without ever naming the package.
 */
private object ComposeCatalog : ComponentCatalog {
    override val palette: List<PaletteEntry> =
        ComposeComponents.specs.map { PaletteEntry(it.type, it.label, it.category) }

    override fun newNode(type: String): Node =
        (ComposeComponents.specFor(type) ?: error("Unknown component type: $type")).create()

    override fun acceptsChildren(type: String): Boolean = ComposeComponents.specFor(type)?.acceptsChildren ?: false

    override fun slotsOf(type: String): List<String> = ComposeComponents.specFor(type)?.slots ?: emptyList()
}

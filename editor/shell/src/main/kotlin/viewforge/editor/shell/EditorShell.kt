package viewforge.editor.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import viewforge.editor.canvas.CanvasRenderer
import viewforge.editor.canvas.EditorCanvas
import viewforge.editor.panels.Inspector
import viewforge.editor.panels.Palette
import viewforge.editor.panels.ThemeEditor
import viewforge.editor.panels.TreePanel
import viewforge.editor.state.EditorState
import viewforge.editor.state.ProjectExportService

/**
 * The top of the editor UI (ARCHITECTURE §2): the application menu bar plus a toolbar and the four
 * working surfaces — palette, tree, canvas, inspector. From M4 the palette is a real mutation surface
 * and the toolbar carries undo/redo/duplicate/delete; global keyboard shortcuts are wired at the
 * focusable root.
 *
 * It is a [FrameWindowScope] extension so it can host the native [AppMenuBar] (#19) itself, reusing its
 * own export controller and theme-dialog state — the menu wiring stays here in the shell rather than
 * leaking up into `:app`. The shell wraps the working surfaces in its **own** `MaterialTheme` — the
 * editor chrome theme, kept deliberately separate from the *project's* theme the canvas renders (S3);
 * the menu bar is native OS chrome and sits outside that theme.
 */
@Composable
fun FrameWindowScope.EditorShell(state: EditorState, renderer: CanvasRenderer, exportService: ProjectExportService) {
    // The theme editor is a modal dialog (M8), opened from the toolbar or the View menu; its state lives
    // here so it survives recomposition and can be dismissed from either the dialog or a re-click.
    var showThemeEditor by remember { mutableStateOf(false) }
    // Export is driven from both the toolbar and the File menu, so its flow is hoisted to a controller.
    val export = rememberExportController(state, exportService)

    AppMenuBar(state, onExport = export::start, onOpenThemeEditor = { showThemeEditor = true })

    MaterialTheme(colorScheme = darkColorScheme()) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }

        if (showThemeEditor) ThemeEditor(state) { showThemeEditor = false }
        ExportDialogs(export)

        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .focusRequester(focus)
                    .focusable()
                    // Root-level shortcuts use onKeyEvent (bubbling) so a focused text field — palette
                    // search, inline rename — consumes its keys first and typing is never hijacked.
                    .onKeyEvent { handleShortcut(it, state) },
            ) {
                Toolbar(state, export, onOpenThemeEditor = { showThemeEditor = true })
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    Palette(state, Modifier.width(180.dp).fillMaxHeight())
                    VerticalDivider()
                    TreePanel(state, Modifier.width(210.dp).fillMaxHeight())
                    VerticalDivider()
                    EditorCanvas(state, renderer, Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider()
                    Inspector(state, Modifier.width(240.dp).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun Toolbar(state: EditorState, export: ExportController, onOpenThemeEditor: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ViewForge — ${state.document.name}",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.weight(1f))
            ToolbarButton("Undo", enabled = state.canUndo, onClick = state::undo)
            ToolbarButton("Redo", enabled = state.canRedo, onClick = state::redo)
            ToolbarButton("Duplicate", enabled = state.selectedNode != null, onClick = state::duplicateSelected)
            ToolbarButton("Delete", enabled = state.selectedNode != null, onClick = state::deleteSelected)
            ToolbarButton("Theme…", enabled = true, onClick = onOpenThemeEditor)
            // Preview the project theme's light/dark values on the canvas (H2); label shows the mode.
            ToolbarButton(
                if (state.canvasDark) "◐ Dark" else "◑ Light",
                enabled = true,
                onClick = state::toggleCanvasDark,
            )
            ExportBar(export)
        }
    }
}

@Composable
internal fun ToolbarButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        },
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * The standard editing shortcuts (S2). Returns true when handled so the event stops here. Ctrl and
 * Meta are both accepted so the same bindings work on macOS. Delete/Backspace remove the selection;
 * Ctrl+D duplicates; Ctrl+C/X/V drive the clipboard; Ctrl+Z / Ctrl+Shift+Z (or Ctrl+Y) undo/redo;
 * Ctrl +/−/0 zoom the canvas (C5). Holding the space bar puts the canvas in pan mode (space-drag).
 */
private fun handleShortcut(event: KeyEvent, state: EditorState): Boolean {
    // Space is the one shortcut that cares about key-up: it's a held modifier for pan mode, not an
    // action. Tracked on both edges before the KeyDown gate below. Because this handler is bubbling,
    // a focused text field consumes its own space first — we only see it when the canvas has focus.
    if (event.key == Key.Spacebar) {
        when (event.type) {
            KeyEventType.KeyDown -> state.isSpaceHeld = true
            KeyEventType.KeyUp -> state.isSpaceHeld = false
            else -> {}
        }
        return state.isSpaceHeld
    }
    if (event.type != KeyEventType.KeyDown) return false
    val cmd = event.isCtrlPressed || event.isMetaPressed
    return when {
        !cmd && (event.key == Key.Delete || event.key == Key.Backspace) -> {
            state.deleteSelected()
            true
        }
        cmd && (event.key == Key.Equals || event.key == Key.Plus) -> {
            state.zoomIn()
            true
        }
        cmd && event.key == Key.Minus -> {
            state.zoomOut()
            true
        }
        cmd && event.key == Key.Zero -> {
            state.resetZoom()
            true
        }
        cmd && event.key == Key.Z && event.isShiftPressed -> {
            state.redo()
            true
        }
        cmd && event.key == Key.Z -> {
            state.undo()
            true
        }
        cmd && event.key == Key.Y -> {
            state.redo()
            true
        }
        cmd && event.key == Key.D -> {
            state.duplicateSelected()
            true
        }
        cmd && event.key == Key.C -> {
            state.copySelected()
            true
        }
        cmd && event.key == Key.X -> {
            state.cut()
            true
        }
        cmd && event.key == Key.V -> {
            state.paste()
            true
        }
        else -> false
    }
}

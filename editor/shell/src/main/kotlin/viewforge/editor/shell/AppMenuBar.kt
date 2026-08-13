package viewforge.editor.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import viewforge.editor.state.CanvasViewport
import viewforge.editor.state.EditorState
import viewforge.editor.state.ExportMode

/**
 * The application menu bar (FEATURES S1/S2, issue #19): a thin native menu over the editor's existing
 * commands and services — File / Edit / View. It holds **no business logic**; every enabled item
 * routes straight to an [EditorState] action or an injected handler, so the menu is a pure view (the
 * same seam the toolbar uses).
 *
 * Two deliberate scoping choices, both documented on the branch:
 *
 * - **Accelerators are displayed, not bound here.** Each edit item shows its keyboard shortcut in its
 *   label, but the *actual* key handling stays in the shell's focus-aware `handleShortcut`. Binding the
 *   shortcut on the menu item instead (`Item(shortcut = …)`) would register a window-global accelerator
 *   that fires regardless of focus — hijacking Ctrl+C/V while the user types in a rename or the palette
 *   search, and double-firing with `handleShortcut`. Display-only keeps the one focus-aware handler
 *   authoritative while still making shortcuts "discoverable in menus" (S2).
 * - **Items without a backing service are shown disabled.** File New/Open/Save/Save As and View
 *   zoom / panel-toggles have no command or state yet; they appear greyed-out so the menu's shape is
 *   discoverable, and light up in their own follow-up issues rather than growing logic in the menu.
 */
@Composable
internal fun FrameWindowScope.AppMenuBar(
    state: EditorState,
    prefs: PreferencesController,
    onExport: (ExportMode) -> Unit,
    onOpenThemeEditor: () -> Unit,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
) {
    val file = state.fileMenuModel()
    val edit = state.editMenuModel()
    val view = state.viewMenuModel()
    MenuBar {
        Menu("File", mnemonic = 'F') {
            // .vforge persistence (#37). Accelerators display-only — handleShortcut binds the real keys.
            Item(withAccel("New", "Ctrl+N"), onClick = onNew)
            Item(withAccel("Open…", "Ctrl+O"), onClick = onOpen)
            Separator()
            // Save greys out with no unsaved edits; Save As is always available (write a copy anywhere).
            Item(withAccel("Save", "Ctrl+S"), enabled = file.canSave, onClick = onSave)
            Item(withAccel("Save As…", "Ctrl+Shift+S"), onClick = onSaveAs)
            Separator()
            // The one File action that has a backing service today (M7 export, ADR-013 seam).
            Item("Export → .kt files", onClick = { onExport(ExportMode.LOOSE_FILES) })
            Item("Export → Gradle project", onClick = { onExport(ExportMode.GRADLE_PROJECT) })
        }
        Menu("Edit", mnemonic = 'E') {
            Item(withAccel("Undo", "Ctrl+Z"), enabled = edit.canUndo, onClick = state::undo)
            Item(withAccel("Redo", "Ctrl+Shift+Z"), enabled = edit.canRedo, onClick = state::redo)
            Separator()
            Item(withAccel("Cut", "Ctrl+X"), enabled = edit.hasSelection, onClick = state::cut)
            Item(withAccel("Copy", "Ctrl+C"), enabled = edit.hasSelection, onClick = state::copySelected)
            Item(withAccel("Paste", "Ctrl+V"), enabled = edit.canPaste, onClick = state::paste)
            Item(withAccel("Duplicate", "Ctrl+D"), enabled = edit.hasSelection, onClick = state::duplicateSelected)
            Separator()
            // Extract the selection into a reusable component (D7). Auto-named to the first free
            // Component<n> (a legal identifier); it appears in the palette immediately (P6a).
            Item(
                "Extract to Component",
                enabled = edit.canExtract,
                onClick = { state.extractSelectionToComponent(state.uniqueComponentName()) },
            )
            Separator()
            Item(withAccel("Delete", "Del"), enabled = edit.hasSelection, onClick = state::deleteSelected)
        }
        Menu("View", mnemonic = 'V') {
            // Preview the project theme's light/dark values on the canvas (H2) — a real toggle.
            CheckboxItem(
                "Dark canvas",
                checked = state.canvasDark,
                onCheckedChange = { state.toggleCanvasDark() },
            )
            Item("Theme…", onClick = onOpenThemeEditor)
            Separator()
            // Canvas zoom (C5). Accelerators are display-only — the shell's handleShortcut binds the
            // real keys — matching the Edit menu's pattern. Zoom In/Out grey out at the clamp bounds.
            Item(withAccel("Zoom In", "Ctrl++"), enabled = view.canZoomIn, onClick = state::zoomIn)
            Item(withAccel("Zoom Out", "Ctrl+-"), enabled = view.canZoomOut, onClick = state::zoomOut)
            Item(withAccel("Reset Zoom", "Ctrl+0"), enabled = view.canResetZoom, onClick = state::resetZoom)
            Separator()
            // Panel visibility (S1, #39) — checked when shown, mirroring the Dark canvas toggle above.
            // Routed through the preferences controller so a toggle persists across sessions (#43).
            CheckboxItem("Palette", checked = state.paletteVisible, onCheckedChange = { prefs.togglePalette() })
            CheckboxItem("Tree", checked = state.treeVisible, onCheckedChange = { prefs.toggleTree() })
            CheckboxItem("Inspector", checked = state.inspectorVisible, onCheckedChange = { prefs.toggleInspector() })
            // The live code preview (G3, #50). Persisted across sessions (#52), so it routes through the
            // preferences controller like the panel toggles above.
            CheckboxItem("Code preview", checked = state.codePreviewVisible, onCheckedChange = {
                prefs.toggleCodePreview()
            })
        }
    }
}

/**
 * The enabled-state the document session gives the File menu: Save lights up only with unsaved edits
 * (New/Open/Save As are always available). Pure data, unit-tested without a composition.
 */
internal data class FileMenuModel(val canSave: Boolean)

internal fun EditorState.fileMenuModel(): FileMenuModel = FileMenuModel(canSave = isDirty)

/**
 * The enabled-state a snapshot of [EditorState] gives the Edit menu. Extracted as pure data so the
 * item→flag wiring (Paste gated on the clipboard, edit-selection actions on a selection, undo/redo on
 * history) is unit-testable without a composition or the framework.
 */
internal data class EditMenuModel(
    val canUndo: Boolean,
    val canRedo: Boolean,
    val hasSelection: Boolean,
    val canPaste: Boolean,
    val canExtract: Boolean,
)

internal fun EditorState.editMenuModel(): EditMenuModel = EditMenuModel(
    canUndo = canUndo,
    canRedo = canRedo,
    hasSelection = selectedNode != null,
    canPaste = canPaste,
    canExtract = canExtractSelection,
)

/**
 * The enabled-state the canvas [viewport][EditorState.viewport] gives the View menu's zoom items:
 * Zoom In/Out grey out at the clamp bounds, Reset only enables when the view is off its default.
 * Pure data, unit-tested without a composition (mirrors [EditMenuModel]).
 */
internal data class ViewMenuModel(val canZoomIn: Boolean, val canZoomOut: Boolean, val canResetZoom: Boolean)

internal fun EditorState.viewMenuModel(): ViewMenuModel = ViewMenuModel(
    canZoomIn = viewport.canZoomIn,
    canZoomOut = viewport.canZoomOut,
    canResetZoom = viewport != CanvasViewport(),
)

/**
 * A menu label that *shows* its accelerator without binding it (see the class note): the shortcut is
 * appended to the text rather than passed as `Item(shortcut = …)`, so `handleShortcut` stays the sole,
 * focus-aware key handler.
 */
internal fun withAccel(text: String, accelerator: String): String = "$text   ($accelerator)"

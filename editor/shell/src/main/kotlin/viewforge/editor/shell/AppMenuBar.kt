package viewforge.editor.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
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
    onExport: (ExportMode) -> Unit,
    onOpenThemeEditor: () -> Unit,
) {
    val edit = state.editMenuModel()
    MenuBar {
        Menu("File", mnemonic = 'F') {
            // No .vforge persistence layer yet (Main.kt: "open/save … arrive in later milestones").
            // Shown disabled so the menu shape is discoverable; wired in a follow-up issue.
            Item("New", enabled = false, onClick = {})
            Item("Open…", enabled = false, onClick = {})
            Separator()
            Item("Save", enabled = false, onClick = {})
            Item("Save As…", enabled = false, onClick = {})
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
            // No zoom or panel-visibility state on the canvas yet; disabled pending follow-up issues.
            Item("Zoom In", enabled = false, onClick = {})
            Item("Zoom Out", enabled = false, onClick = {})
            Item("Reset Zoom", enabled = false, onClick = {})
            Separator()
            Item("Toggle Palette", enabled = false, onClick = {})
            Item("Toggle Tree", enabled = false, onClick = {})
            Item("Toggle Inspector", enabled = false, onClick = {})
        }
    }
}

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
)

internal fun EditorState.editMenuModel(): EditMenuModel = EditMenuModel(
    canUndo = canUndo,
    canRedo = canRedo,
    hasSelection = selectedNode != null,
    canPaste = canPaste,
)

/**
 * A menu label that *shows* its accelerator without binding it (see the class note): the shortcut is
 * appended to the text rather than passed as `Item(shortcut = …)`, so `handleShortcut` stays the sole,
 * focus-aware key handler.
 */
internal fun withAccel(text: String, accelerator: String): String = "$text   ($accelerator)"

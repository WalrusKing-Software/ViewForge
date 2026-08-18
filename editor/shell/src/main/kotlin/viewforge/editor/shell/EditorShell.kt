package viewforge.editor.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import viewforge.editor.canvas.CanvasRenderer
import viewforge.editor.canvas.EditorCanvas
import viewforge.editor.panels.CodePreview
import viewforge.editor.panels.Inspector
import viewforge.editor.panels.Palette
import viewforge.editor.panels.ThemeEditor
import viewforge.editor.panels.TreePanel
import viewforge.editor.state.CodePreviewService
import viewforge.editor.state.DeviceProfile
import viewforge.editor.state.DeviceProfiles
import viewforge.editor.state.EditorState
import viewforge.editor.state.ProjectExportService
import java.nio.file.Path

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
fun FrameWindowScope.EditorShell(
    state: EditorState,
    renderer: CanvasRenderer,
    exportService: ProjectExportService,
    previewService: CodePreviewService,
    recoveryDir: Path,
    // The per-user config-dir folder the cross-project component library persists to (ADR-033, #209),
    // supplied by :app like [recoveryDir]. The library controller loads it into the palette at startup.
    libraryDir: Path,
    // Save-on-close (#56): the window's onCloseRequest raises [closeRequested] when the document is dirty;
    // the shell shows a Save/Discard/Cancel prompt and calls [onExit] to actually quit or [onCloseHandled]
    // to abort. Defaulted so the shell stays usable (and the signature stable) without the close wiring.
    closeRequested: Boolean = false,
    onCloseHandled: () -> Unit = {},
    onExit: () -> Unit = {},
) {
    // The theme editor is a modal dialog (M8), opened from the toolbar or the View menu; its state lives
    // here so it survives recomposition and can be dismissed from either the dialog or a re-click.
    var showThemeEditor by remember { mutableStateOf(false) }
    // Export is driven from both the toolbar and the File menu, so its flow is hoisted to a controller.
    val export = rememberExportController(state, exportService)
    // Preferences persistence (#43/#55/#88/#104/#105): panel layout, chrome theme, recent projects, and the
    // S5 editor settings. Created before the document controller, which records opened/saved paths through it.
    val prefs = rememberPreferencesController(state)
    // In-app Preferences dialog (S5, #105): a modal editing the persisted editor settings, opened from the
    // File menu. Its visibility lives here so it survives recomposition, like the theme editor.
    var showPreferences by remember { mutableStateOf(false) }
    // The command palette (S4, #123): a Ctrl+Shift+P fuzzy launcher over the editor's actions. Its
    // visibility lives here like the dialogs above; the command catalog is rebuilt from the live wiring
    // each time it opens, so every command's enabled-state is current.
    var showPalette by remember { mutableStateOf(false) }
    // .vforge New/Open/Save/Save As (#37) + Open Recent (#88): its own controller, shared by the File
    // menu and shortcuts.
    val document = rememberDocumentController(state, prefs)
    // Image import (#141): the inspector requests an import; this controller does the disk work (pick,
    // guarded copy into the project's assets/ dir, bind to the node). Observed off state so the panel
    // names no shell/disk type — the same request-bridge as inline rename. Blocking chooser on the UI
    // thread, like the document controller.
    val assetImport = rememberAssetImportController(state)
    LaunchedEffect(assetImport) {
        snapshotFlow { state.imageImportRequest }
            .filterNotNull()
            .collect { request ->
                assetImport.handle(request)
                state.clearImageImportRequest()
            }
    }
    // Autosave + crash recovery (D4): a timer snapshots unsaved work; a snapshot found at launch prompts
    // to restore. The config dir comes from :app (the wiring site), like the panel-layout load. The cadence
    // is the live S5 preference (#105): keying the timer on it re-drives the interval without a restart.
    val recovery = rememberRecoveryController(state, recoveryDir)
    LaunchedEffect(recovery, state.autosaveIntervalSeconds) {
        while (true) {
            delay(state.autosaveIntervalSeconds * 1000L)
            recovery.tick()
        }
    }
    // The cross-project component library (ADR-033, #209): loads the config-dir folder into the palette at
    // startup and owns add/remove/rename + the copy-into-document insert. A one-frame delay before library
    // entries appear is fine (unlike panel layout, this is not chrome that would flash).
    val library = rememberLibraryController(state, libraryDir)
    LaunchedEffect(library) { library.reload() }

    AppMenuBar(
        state,
        prefs,
        onExport = export::start,
        onRegenerate = export::regenerate,
        onOpenThemeEditor = { showThemeEditor = true },
        onOpenPreferences = { showPreferences = true },
        onOpenLibraryManager = library::openManager,
        onNew = document::newDocument,
        onOpen = document::open,
        onOpenGenerated = document::openGenerated,
        onOpenRecent = document::openRecent,
        onClearRecent = prefs::clearRecent,
        onSave = document::save,
        onSaveAs = document::saveAs,
    )

    // Editor chrome theme (S3, #104), independent of the project preview the canvas renders: a View-menu
    // toggle flips it and prefs persist it. Defaults dark, matching the previously hardcoded scheme.
    MaterialTheme(colorScheme = if (state.chromeDark) darkColorScheme() else lightColorScheme()) {
        val focus = remember { FocusRequester() }
        // The root Column owns the global keyboard shortcuts (S2/D1). requestFocus() is a no-op while
        // the window is not yet focusable, so a bare LaunchedEffect(Unit) at first composition silently
        // fails and the shortcuts stay dead until the user clicks a focusable child (#157). Re-request
        // each time the window gains focus so the root reliably reclaims key input — on launch and after
        // alt-tabbing back. snapshotFlow keeps the focus read out of composition so it never re-runs the
        // shell subtree.
        val windowInfo = LocalWindowInfo.current
        LaunchedEffect(windowInfo, focus) {
            snapshotFlow { windowInfo.isWindowFocused }
                .filter { it }
                .collect { focus.requestFocus() }
        }

        if (showThemeEditor) ThemeEditor(state) { showThemeEditor = false }
        if (showPreferences) PreferencesDialog(state, prefs) { showPreferences = false }
        ExportDialogs(export)
        DocumentDialogs(document)
        AssetImportDialogs(assetImport)
        RecoveryDialog(recovery)
        ManageLibraryDialog(library, state)
        LibraryInsertDialog(library, state)
        ExitConfirmation(
            closeRequested,
            state,
            document,
            onExit = onExit,
            onCancel = onCloseHandled,
            onSavedClean = recovery::clearIfClean,
        )
        // The command palette (S4, #123). Built from the live wiring so enablement is current when opened.
        if (showPalette) {
            CommandPalette(
                buildPaletteCommands(
                    state,
                    document,
                    prefs,
                    export,
                    onOpenThemeEditor = { showThemeEditor = true },
                    onOpenPreferences = { showPreferences = true },
                    onInsert = library::insert,
                    onOpenLibraryManager = library::openManager,
                ),
            ) { showPalette = false }
        }

        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .focusRequester(focus)
                    .focusable()
                    // Undo/redo must win even over a focused text field, which otherwise swallows
                    // Ctrl+Z / Ctrl+Y as its own text-undo before it reaches the root (#166). The
                    // capture phase (onPreviewKeyEvent) sees these chords first, top-down; every other
                    // key returns false and continues down to the field and on to the bubbling handler,
                    // so in-field typing, clipboard (Ctrl+C/X/V) and select-all stay with the field.
                    .onPreviewKeyEvent { handleUndoRedoShortcut(it, state) }
                    // The remaining root shortcuts use onKeyEvent (bubbling) so a focused text field —
                    // palette search, inline rename — consumes its keys first and typing is never
                    // hijacked. Editing shortcuts first, then the File-menu document shortcuts.
                    .onKeyEvent {
                        handlePaletteShortcut(it) { showPalette = true } ||
                            handlePanelShortcut(it, prefs) ||
                            handleShortcut(it, state) ||
                            handleDocumentShortcut(it, document)
                    },
            ) {
                Toolbar(state, export, onOpenThemeEditor = { showThemeEditor = true })
                HorizontalDivider()
                // The screen switcher (D6) — or, while a component is open for in-place editing (#61), a
                // breadcrumb back to the screen in its place (you don't switch screens inside a component).
                if (state.editingComponentId != null) ComponentEditBar(state) else ScreenSwitcher(state)
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    // Each side panel and its adjacent divider hide together (S1, #39); the canvas keeps
                    // weight(1f) and is always shown, so hiding everything still leaves an edit surface.
                    // The divider is a drag-to-resize splitter (S1, #43): the panel's own width lives in
                    // state (persisted across sessions), and the drag grows it toward the canvas — so the
                    // left panels add the delta and the right-hand inspector subtracts it.
                    if (state.paletteVisible) {
                        Palette(
                            state,
                            prefs::toggleFavorite,
                            onInsert = library::insert,
                            onDropLibrary = library::dropDrag,
                            Modifier.width(state.paletteWidth.dp).fillMaxHeight(),
                        )
                        ResizableDivider(onResize = state::resizePalette, onCommit = prefs::persist)
                    }
                    if (state.treeVisible) {
                        TreePanel(state, Modifier.width(state.treeWidth.dp).fillMaxHeight())
                        ResizableDivider(onResize = state::resizeTree, onCommit = prefs::persist)
                    }
                    EditorCanvas(state, renderer, Modifier.weight(1f).fillMaxHeight())
                    if (state.inspectorVisible) {
                        ResizableDivider(onResize = { state.resizeInspector(-it) }, onCommit = prefs::persist)
                        Inspector(state, Modifier.width(state.inspectorWidth.dp).fillMaxHeight())
                    }
                    // The live code preview (G3, #50) sits furthest right. Its visibility and width are
                    // persisted across sessions (#52), so a resize commits through the same controller as
                    // the side panels above.
                    if (state.codePreviewVisible) {
                        ResizableDivider(onResize = { state.resizeCodePreview(-it) }, onCommit = prefs::persist)
                        CodePreview(state, previewService, Modifier.width(state.codePreviewWidth.dp).fillMaxHeight())
                    }
                }
            }
        }
        // The right-click context menu (#160), positioned at the window-space point the tree/canvas
        // recorded. A sibling of the content above so its anchor shares the same origin as those coords.
        ContextMenuOverlay(state)
    }
}

// FlowRow lets the action buttons wrap to a second line at narrow widths instead of clipping off the
// edge (#161). Stable layout in CMP 1.7.3, still annotated as experimental.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Toolbar(state: EditorState, export: ExportController, onOpenThemeEditor: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            // Top-align so the title lines up with the first row of actions when they wrap.
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                // A trailing • marks unsaved edits (D1), the same cue the window title conventions use.
                text = "ViewForge — ${state.document.name}${if (state.isDirty) " •" else ""}",
                style = MaterialTheme.typography.titleSmall,
                // Keep vertical rhythm with the label-styled buttons beside it.
                modifier = Modifier.padding(vertical = 4.dp),
            )
            // Actions take the remaining width and stay right-aligned; overflow wraps to a new line.
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ToolbarButton("Undo", enabled = state.canUndo, onClick = state::undo)
                ToolbarButton("Redo", enabled = state.canRedo, onClick = state::redo)
                ToolbarButton("Duplicate", enabled = state.selectedNode != null, onClick = state::duplicateSelected)
                ToolbarButton("Delete", enabled = state.selectedNode != null, onClick = state::deleteSelected)
                ToolbarButton("Theme…", enabled = true, onClick = onOpenThemeEditor)
                // Interactive preview / run mode (C13, #120): flip between editing and interacting with the live UI.
                ToolbarButton(
                    if (state.interactivePreview) "◼ Exit preview" else "▶ Preview",
                    enabled = true,
                    onClick = state::toggleInteractivePreview,
                )
                DeviceProfileSelector(state)
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
}

/**
 * The device-preview-frame selector (C6): a dropdown showing the active screen's profile and offering
 * the Phase-1 desktop sizes. Selecting one runs the undoable `SetPreviewProfile` command through
 * [EditorState], and the canvas reframes to it. A view over state like the rest of the toolbar.
 */
@Composable
private fun DeviceProfileSelector(state: EditorState) {
    var expanded by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    Box {
        ToolbarButton("◱ ${state.activeDeviceProfile.label}", enabled = true, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DeviceProfiles.ALL.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.label) },
                    onClick = {
                        expanded = false
                        state.setPreviewProfile(profile.id)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Custom size…") },
                onClick = {
                    expanded = false
                    customOpen = true
                },
            )
        }
    }
    if (customOpen) {
        CustomSizeDialog(
            initial = state.activeDeviceProfile,
            onDismiss = { customOpen = false },
            onConfirm = { w, h ->
                customOpen = false
                state.setPreviewProfile(DeviceProfiles.customProfileId(w, h))
            },
        )
    }
}

/**
 * A small dialog for an arbitrary canvas frame size (#163). Width/height are entered in dp and must be
 * whole numbers within [DeviceProfiles.MIN_DIMENSION]..[DeviceProfiles.MAX_DIMENSION]; OK is disabled
 * until both are valid, so an invalid size never reaches [DeviceProfiles.customProfileId]. Seeded from the
 * currently active profile so "custom" starts from what's on screen.
 */
@Composable
private fun CustomSizeDialog(
    initial: DeviceProfile,
    onDismiss: () -> Unit,
    onConfirm: (width: Int, height: Int) -> Unit,
) {
    var widthText by remember { mutableStateOf(initial.width.toInt().toString()) }
    var heightText by remember { mutableStateOf(initial.height.toInt().toString()) }
    val width = widthText.toIntOrNull()
    val height = heightText.toIntOrNull()
    val range = DeviceProfiles.MIN_DIMENSION..DeviceProfiles.MAX_DIMENSION
    val valid = width != null && height != null && width in range && height in range

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom canvas size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { widthText = it.filter(Char::isDigit).take(5) },
                        label = { Text("Width") },
                        singleLine = true,
                        modifier = Modifier.width(120.dp),
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { heightText = it.filter(Char::isDigit).take(5) },
                        label = { Text("Height") },
                        singleLine = true,
                        modifier = Modifier.width(120.dp),
                    )
                }
                Text(
                    "In dp, ${DeviceProfiles.MIN_DIMENSION}–${DeviceProfiles.MAX_DIMENSION}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(width!!, height!!) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ToolbarButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        // Keep each label whole on one line so wrapping happens at button boundaries, never mid-label (#161).
        maxLines = 1,
        softWrap = false,
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
 * Undo/redo (S2), handled in the capture phase so it wins even when a text field is focused (#166): a
 * focused [androidx.compose.foundation.text.BasicTextField] treats Ctrl+Z / Ctrl+Y as its own text-undo
 * and would swallow the chord before the bubbling [handleShortcut] ever runs, leaving document undo dead
 * while an inspector or rename field has focus. Kept to this narrow pair so the field still owns its own
 * clipboard and select-all — only undo/redo is claimed root-wide. Ctrl and Meta both accepted (macOS);
 * only on KeyDown; Ctrl+Shift+Z and Ctrl+Y are both redo.
 */
private fun handleUndoRedoShortcut(event: KeyEvent, state: EditorState): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val cmd = event.isCtrlPressed || event.isMetaPressed
    if (!cmd) return false
    return when {
        event.key == Key.Z && event.isShiftPressed -> {
            state.redo()
            true
        }
        event.key == Key.Z -> {
            state.undo()
            true
        }
        event.key == Key.Y -> {
            state.redo()
            true
        }
        else -> false
    }
}

/**
 * The standard editing shortcuts (S2). Returns true when handled so the event stops here. Ctrl and
 * Meta are both accepted so the same bindings work on macOS. Delete/Backspace remove the selection;
 * Ctrl+D duplicates; Ctrl+C/X/V drive the clipboard; Ctrl +/−/0 zoom the canvas (C5) and Ctrl+9 fits
 * the device frame (C6). Holding the space bar puts the canvas in pan mode (space-drag). Undo/redo is
 * handled separately in [handleUndoRedoShortcut] (capture phase) so a focused text field can't swallow it.
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
    // M (unmodified) is likewise a held key, not an action: hold it to show the measure/spacing overlay
    // (C12, #119). Tracked on both edges like space; a Cmd/Ctrl-M chord is left alone. Bubbling, so a
    // focused text field types its own 'm' first and we only see it when the canvas has focus.
    if (event.key == Key.M && !event.isCtrlPressed && !event.isMetaPressed) {
        when (event.type) {
            KeyEventType.KeyDown -> state.isMeasuring = true
            KeyEventType.KeyUp -> state.isMeasuring = false
            else -> {}
        }
        return state.isMeasuring
    }
    if (event.type != KeyEventType.KeyDown) return false
    val cmd = event.isCtrlPressed || event.isMetaPressed
    return when {
        !cmd && (event.key == Key.Delete || event.key == Key.Backspace) -> {
            state.deleteSelected()
            true
        }
        !cmd && event.key == Key.F2 -> {
            state.requestRenameSelected()
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
        cmd && event.key == Key.Nine -> {
            state.fitToFrame()
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

/**
 * The command-palette shortcut (S4, #123): Ctrl+Shift+P (Cmd on macOS) opens the fuzzy launcher. Checked
 * before the editing/document shortcuts so it wins the chord, and only on KeyDown. Once open, the palette's
 * focusable popup captures keys, so this never competes with typing a query.
 */
private fun handlePaletteShortcut(event: KeyEvent, onOpen: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val cmd = event.isCtrlPressed || event.isMetaPressed
    if (cmd && event.isShiftPressed && event.key == Key.P) {
        onOpen()
        return true
    }
    return false
}

/** The four editor panels that toggle from the keyboard (S1, #208). */
internal enum class EditorPanel { PALETTE, TREE, INSPECTOR, CODE_PREVIEW }

/**
 * The pure Ctrl/Cmd+1..4 → panel mapping (#208): 1 Palette, 2 Tree, 3 Inspector, 4 Code preview. The
 * digits are collision-free with the existing shortcuts (Ctrl+0/9 reset/fit, Ctrl+± zoom, the Ctrl+letter
 * edit/document actions). Requires the platform command modifier and rejects Shift/Alt chords so those
 * combinations stay free for the future. Extracted from [KeyEvent] so the mapping is unit-testable
 * without synthesising a key event (mirroring the pure menu models).
 */
internal fun panelForShortcut(key: Key, cmd: Boolean, shift: Boolean, alt: Boolean): EditorPanel? {
    if (!cmd || shift || alt) return null
    return when (key) {
        Key.One -> EditorPanel.PALETTE
        Key.Two -> EditorPanel.TREE
        Key.Three -> EditorPanel.INSPECTOR
        Key.Four -> EditorPanel.CODE_PREVIEW
        else -> null
    }
}

/**
 * The panel show/hide shortcuts (S1, #208): Ctrl/Cmd+1..4 toggle the four editor panels through the same
 * [PreferencesController] toggles the View menu uses, so a keyboard toggle persists across sessions like a
 * menu one. Checked after the palette shortcut and before the editing/document shortcuts; the digits don't
 * overlap those, so ordering is only for readability. The real binder — the menu labels only display.
 */
private fun handlePanelShortcut(event: KeyEvent, prefs: PreferencesController): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val panel = panelForShortcut(
        event.key,
        cmd = event.isCtrlPressed || event.isMetaPressed,
        shift = event.isShiftPressed,
        alt = event.isAltPressed,
    ) ?: return false
    when (panel) {
        EditorPanel.PALETTE -> prefs.togglePalette()
        EditorPanel.TREE -> prefs.toggleTree()
        EditorPanel.INSPECTOR -> prefs.toggleInspector()
        EditorPanel.CODE_PREVIEW -> prefs.toggleCodePreview()
    }
    return true
}

/**
 * The File-menu document shortcuts (D1), kept separate from [handleShortcut] because they act on the
 * [DocumentController] rather than [EditorState]. Ctrl+N new, Ctrl+O open, Ctrl+S save, Ctrl+Shift+S
 * save as. As with the editing shortcuts, this is the real binder — the menu accelerators only display.
 */
private fun handleDocumentShortcut(event: KeyEvent, document: DocumentController): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val cmd = event.isCtrlPressed || event.isMetaPressed
    if (!cmd) return false
    return when {
        event.key == Key.N -> {
            document.newDocument()
            true
        }
        event.key == Key.O -> {
            document.open()
            true
        }
        event.key == Key.S && event.isShiftPressed -> {
            document.saveAs()
            true
        }
        event.key == Key.S -> {
            document.save()
            true
        }
        else -> false
    }
}

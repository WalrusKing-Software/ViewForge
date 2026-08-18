package viewforge.editor.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import viewforge.editor.state.EditorState

/**
 * The command palette (FEATURES S4, issue #123): a Ctrl+Shift+P fuzzy launcher for the editor's actions.
 * Like the menu bar it holds **no business logic** — every command routes to an existing [EditorState]
 * action or an injected controller, so the palette is a pure view over the same wiring the menu uses
 * (its enablement even reuses the menu models). Matching and ranking are a pure, Compose-free function
 * ([rankCommands]) so they can be unit-tested without a UI, mirroring the layers-panel tree search (#122).
 */

/**
 * One entry in the command palette. Only [title] and [category] drive matching, so the ranker can be
 * tested with dummy commands (default [enabled]/[run]); [enabled] and [run] are supplied by the shell
 * wiring in [buildPaletteCommands]. A disabled command still appears (greyed) but cannot be invoked, the
 * same way the menu greys an unavailable item.
 */
internal data class PaletteCommand(
    val id: String,
    val title: String,
    val category: String,
    val enabled: Boolean = true,
    val run: () -> Unit = {},
)

/**
 * Rank [commands] for [query]: a case-insensitive fuzzy match on the command title, best first. A blank
 * query keeps the full catalog in its natural (menu) order. Otherwise a command is kept only if the query
 * is a subsequence of its title, tiered exact > prefix > contiguous-substring > subsequence, with the
 * original catalog index as a stable tie-break. Pure — no composition or framework needed.
 */
internal fun rankCommands(query: String, commands: List<PaletteCommand>): List<PaletteCommand> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return commands
    return commands
        .mapIndexedNotNull { index, command ->
            commandScore(needle, command.title.lowercase())?.let { score -> Triple(score, index, command) }
        }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .map { it.third }
}

/**
 * A match score for [needle] against [hay] (both already lower-cased), or null when [needle] is not even
 * a subsequence of [hay]. Lower is better: exact match, then a prefix, then a contiguous substring (earlier
 * is better), then a scattered subsequence (tighter is better). The tiers are spread far apart so a worse
 * kind can never out-rank a better one.
 */
private fun commandScore(needle: String, hay: String): Int? {
    if (needle == hay) return 0
    if (hay.startsWith(needle)) return 1
    val substringAt = hay.indexOf(needle)
    if (substringAt >= 0) return 100 + substringAt
    // Subsequence: match every needle char in order; score by how spread out the matched span is.
    var first = -1
    var last = -1
    var h = 0
    for (c in needle) {
        h = hay.indexOf(c, h)
        if (h < 0) return null
        if (first < 0) first = h
        last = h
        h++
    }
    return 1000 + (last - first)
}

/**
 * The command catalog, assembled where every action's wiring already converges (mirroring [AppMenuBar]):
 * the File / Edit / View menu actions with their [EditMenuModel]/[FileMenuModel]/[ViewMenuModel]
 * enablement, plus the dynamic "Go to screen" entries and "Add" entries from the live palette (cycle-gated
 * like the palette itself). Rebuilt each time the palette opens so every enabled-state is current.
 */
internal fun buildPaletteCommands(
    state: EditorState,
    document: DocumentController,
    prefs: PreferencesController,
    export: ExportController,
    onOpenThemeEditor: () -> Unit,
    onOpenPreferences: () -> Unit,
): List<PaletteCommand> {
    val file = state.fileMenuModel()
    val edit = state.editMenuModel()
    val view = state.viewMenuModel()
    val commands = mutableListOf<PaletteCommand>()

    // File
    commands += PaletteCommand("file.new", "New Project", "File", run = document::newDocument)
    commands += PaletteCommand("file.open", "Open Project…", "File", run = document::open)
    commands += PaletteCommand("file.openGenerated", "Open Generated .kt…", "File", run = document::openGenerated)
    commands += PaletteCommand("file.save", "Save", "File", enabled = file.canSave, run = document::save)
    commands += PaletteCommand("file.saveAs", "Save As…", "File", run = document::saveAs)
    commands += PaletteCommand("file.exportKt", "Export → .kt files", "File") {
        export.start(viewforge.editor.state.ExportMode.LOOSE_FILES)
    }
    commands += PaletteCommand("file.exportGradle", "Export → Gradle project", "File") {
        export.start(viewforge.editor.state.ExportMode.GRADLE_PROJECT)
    }
    commands += PaletteCommand("file.regenerate", "Regenerate Gradle project…", "File", run = export::regenerate)
    commands += PaletteCommand("file.preferences", "Preferences…", "File", run = onOpenPreferences)

    // Edit
    commands += PaletteCommand("edit.undo", "Undo", "Edit", enabled = edit.canUndo, run = state::undo)
    commands += PaletteCommand("edit.redo", "Redo", "Edit", enabled = edit.canRedo, run = state::redo)
    commands += PaletteCommand("edit.cut", "Cut", "Edit", enabled = edit.hasSelection, run = state::cut)
    commands += PaletteCommand("edit.copy", "Copy", "Edit", enabled = edit.hasSelection, run = state::copySelected)
    commands += PaletteCommand("edit.paste", "Paste", "Edit", enabled = edit.canPaste, run = state::paste)
    commands += PaletteCommand(
        "edit.duplicate",
        "Duplicate",
        "Edit",
        enabled = edit.hasSelection,
        run = state::duplicateSelected,
    )
    commands += PaletteCommand("edit.extract", "Extract to Component", "Edit", enabled = edit.canExtract) {
        state.extractSelectionToComponent(state.uniqueComponentName())
    }
    commands += PaletteCommand(
        "edit.saveScreenAsComponent",
        "Save Screen as Component",
        "Edit",
        enabled = state.activeScreenId != null,
    ) {
        state.activeScreenId?.let { state.saveScreenAsComponent(it, state.defaultComponentNameForScreen(it)) }
    }
    commands +=
        PaletteCommand("edit.delete", "Delete", "Edit", enabled = edit.hasSelection, run = state::deleteSelected)

    // View
    commands += PaletteCommand("view.canvasDark", "Toggle Dark canvas", "View", run = state::toggleCanvasDark)
    commands += PaletteCommand("view.chromeDark", "Toggle Dark editor", "View", run = prefs::toggleChromeDark)
    commands += PaletteCommand("view.theme", "Theme…", "View", run = onOpenThemeEditor)
    commands += PaletteCommand("view.borders", "Toggle Show borders", "View", run = state::toggleShowBorders)
    commands += PaletteCommand("view.guides", "Toggle Alignment guides", "View", run = state::toggleShowGuides)
    commands += PaletteCommand(
        "view.interactive",
        "Toggle Interactive preview",
        "View",
        run = state::toggleInteractivePreview,
    )
    commands += PaletteCommand("view.zoomIn", "Zoom In", "View", enabled = view.canZoomIn, run = state::zoomIn)
    commands += PaletteCommand("view.zoomOut", "Zoom Out", "View", enabled = view.canZoomOut, run = state::zoomOut)
    commands += PaletteCommand(
        "view.resetZoom",
        "Reset Zoom",
        "View",
        enabled = view.canResetZoom,
        run = state::resetZoom,
    )
    commands += PaletteCommand("view.fit", "Fit to Frame", "View", enabled = view.canFit) { state.fitToFrame() }
    commands += PaletteCommand("view.palette", "Toggle Palette", "View", run = prefs::togglePalette)
    commands += PaletteCommand("view.tree", "Toggle Tree", "View", run = prefs::toggleTree)
    commands += PaletteCommand("view.inspector", "Toggle Inspector", "View", run = prefs::toggleInspector)
    commands += PaletteCommand("view.codePreview", "Toggle Code preview", "View", run = prefs::toggleCodePreview)
    commands += PaletteCommand("view.wrap", "Toggle Wrap code preview", "View", run = prefs::toggleCodePreviewWrap)

    // Go to screen (dynamic). Switching leaves an open component (you can't switch screens inside one, #61).
    state.document.screens.forEach { screen ->
        commands += PaletteCommand("screen.${screen.id}", "Go to screen: ${screen.name}", "Go to screen") {
            state.closeComponent()
            state.activeScreenId = screen.id
        }
    }

    // Add component (dynamic): every live palette entry, gated on the same cycle check the palette uses.
    state.palette.forEach { entry ->
        commands += PaletteCommand(
            id = "add.${entry.componentId ?: entry.type}",
            title = "Add ${entry.label}",
            category = "Add component",
            enabled = !state.paletteEntryWouldCycle(entry),
            run = { state.addFromPalette(entry) },
        )
    }

    return commands
}

/**
 * The palette overlay: a centered popup with a search field and a ranked, keyboard-navigable list. Down/Up
 * move the selection (skipping disabled commands), Enter invokes it and closes, Escape or an outside click
 * dismisses. The popup is focusable so it captures keys itself — the shell's root shortcut handler never
 * sees them while the palette is open, so typing a query is never hijacked.
 */
@Composable
internal fun CommandPalette(commands: List<PaletteCommand>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = rankCommands(query, commands)
    var selected by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    // Keep the selection on an enabled row as the results change under typing.
    LaunchedEffect(query) { selected = results.firstEnabledIndexFrom(0, +1) }
    LaunchedEffect(selected) { if (selected in results.indices) listState.animateScrollToItem(selected) }

    fun move(direction: Int) {
        val next = results.firstEnabledIndexFrom(selected + direction, direction)
        if (next >= 0) selected = next
    }

    fun invoke() {
        results.getOrNull(selected)?.takeIf { it.enabled }?.let {
            it.run()
            onDismiss()
        }
    }

    Popup(
        alignment = Alignment.TopCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier.padding(top = 80.dp).width(560.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(6.dp)) {
                // Focus the search field on open. This must live inside the Popup content: the requester
                // is attached to the field below (.focusRequester(focus)), and the Popup composes as a
                // separate sub-composition — requesting from the outer body runs before that node is
                // attached and throws "FocusRequester is not initialized" (#168).
                LaunchedEffect(Unit) { focus.requestFocus() }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                "Type a command…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    move(+1)
                                    true
                                }
                                Key.DirectionUp -> {
                                    move(-1)
                                    true
                                }
                                Key.Enter -> {
                                    invoke()
                                    true
                                }
                                Key.Escape -> {
                                    onDismiss()
                                    true
                                }
                                else -> false
                            }
                        },
                )
                HorizontalDivider()
                if (results.isEmpty()) {
                    Text(
                        "No matching commands",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.heightIn(max = 360.dp)) {
                        itemsIndexed(results, key = { _, c -> c.id }) { index, command ->
                            CommandRow(
                                command = command,
                                selected = index == selected,
                                onClick = {
                                    if (command.enabled) {
                                        command.run()
                                        onDismiss()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One command row: title on the left, category dimmed on the right; greyed when disabled, tinted when selected. */
@Composable
private fun CommandRow(command: PaletteCommand, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val content = when {
        !command.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .clickable(enabled = command.enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = command.title,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = command.category,
            style = MaterialTheme.typography.labelSmall,
            color = if (command.enabled) MaterialTheme.colorScheme.onSurfaceVariant else content,
            maxLines = 1,
        )
    }
}

/**
 * The index of the first enabled command at or after [start] scanning in [direction] (+1 down, −1 up), or
 * −1 if there is none. Used for keyboard navigation so disabled rows are skipped over rather than landed on.
 */
private fun List<PaletteCommand>.firstEnabledIndexFrom(start: Int, direction: Int): Int {
    var i = start
    while (i in indices) {
        if (this[i].enabled) return i
        i += direction
    }
    return -1
}

package viewforge.editor.shell

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import viewforge.editor.state.EditorState
import viewforge.editor.state.PaletteEntry
import viewforge.prefs.PreferencesStore
import java.awt.Cursor
import java.nio.file.Path

/**
 * Persists the editor's **panel layout** — the #39 visibility flags (including the #52 code-preview
 * panel) plus the #43 panel widths — across sessions (S1, ADR-023). Remembered once in the shell, like
 * [DocumentController].
 *
 * It names no framework: it saves through [PreferencesStore] in `core/prefs`, exactly as the document
 * controller saves through `ProjectStore` (the #37 no-seam precedent). Startup *load* happens in `:app`
 * (the wiring site) so there is no first-frame layout flash; this controller owns the *save* side.
 *
 * A save is triggered at the discrete points layout actually changes — a visibility toggle, and the end
 * of a resize drag — so there is no per-pixel write during a drag. Saves are best-effort: a failure to
 * persist chrome must never interrupt editing, so it is swallowed (the same reasoning that makes
 * [PreferencesStore.load] fall back to defaults).
 */
internal class PreferencesController(private val state: EditorState) {
    fun togglePalette() {
        state.togglePalette()
        persist()
    }

    fun toggleTree() {
        state.toggleTree()
        persist()
    }

    fun toggleInspector() {
        state.toggleInspector()
        persist()
    }

    fun toggleCodePreview() {
        state.toggleCodePreview()
        persist()
    }

    /** Flip code-preview soft-wrap and persist the choice across sessions (#115). */
    fun toggleCodePreviewWrap() {
        state.toggleCodePreviewWrap()
        persist()
    }

    /** Flip the editor chrome light/dark and persist the choice across sessions (S3, #104). */
    fun toggleChromeDark() {
        state.toggleChromeDark()
        persist()
    }

    /** Set the autosave cadence from the Preferences dialog and persist it (S5, #105). */
    fun setAutosaveInterval(seconds: Int) {
        state.updateAutosaveInterval(seconds)
        persist()
    }

    /** Set the undo depth from the Preferences dialog and persist it (S5, #105). */
    fun setHistoryDepth(entries: Int) {
        state.updateHistoryDepth(entries)
        persist()
    }

    /** Set the default export directory from the Preferences dialog and persist it (S5, #105). */
    fun setDefaultExportPath(path: String) {
        state.updateDefaultExportPath(path)
        persist()
    }

    /** Record [path] as the most-recent project and persist the updated list (D8). */
    fun recordRecent(path: Path) {
        state.noteRecentProject(path.toString())
        persist()
    }

    /** Drop [path] from the recent list (it no longer opens) and persist (D8). */
    fun forgetRecent(path: String) {
        state.removeRecentProject(path)
        persist()
    }

    /** Clear the recent-projects list and persist (D8). */
    fun clearRecent() {
        state.clearRecentProjects()
        persist()
    }

    /** Pin or unpin a palette [entry] as a favorite and persist the updated list (P5a, #121). */
    fun toggleFavorite(entry: PaletteEntry) {
        state.toggleFavorite(entry)
        persist()
    }

    /** Dismiss the current project's one-time interactive-code acknowledgment and persist it (ADR-035, #277). */
    fun acknowledgeInteractive() {
        state.acknowledgeInteractive()
        persist()
    }

    /**
     * Persist the current preferences. Triggered at the discrete points chrome changes — a visibility
     * toggle, the end of a resize drag, or a recent-projects change.
     *
     * It **load-merge-saves**: it reads the file, overlays only the facets held live in [state] — panel
     * layout, recent projects, chrome theme, favorite components (P5a), the last-open project path (#156),
     * and the S5 editor settings (autosave interval, history depth, default export path) — and writes that
     * back, so persisting one facet never clobbers another. Every
     * persisted preference now has an in-memory home in [state], so the load-merge only guards against a
     * newer build's unknown keys.
     */
    fun persist() {
        runCatching {
            val current = PreferencesStore.load()
            PreferencesStore.save(
                current.copy(
                    panelLayout = state.panelLayout(),
                    recentProjects = state.recentProjects,
                    chromeDark = state.chromeDark,
                    autosaveIntervalSeconds = state.autosaveIntervalSeconds,
                    historyDepth = state.historyDepth,
                    defaultExportPath = state.defaultExportPath,
                    favoriteComponents = state.favoriteComponents,
                    acknowledgedInteractive = state.acknowledgedInteractive,
                    // Remember the current file so the next launch restores it (#156); blank for a
                    // never-saved or New document, which then opens a blank canvas next time.
                    lastProjectPath = state.currentPath?.toString().orEmpty(),
                ),
            )
        }
    }
}

@Composable
internal fun rememberPreferencesController(state: EditorState): PreferencesController =
    remember(state) { PreferencesController(state) }

private val RESIZE_CURSOR = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

/**
 * A draggable vertical splitter between two panels (S1, #43). It renders as the same hairline
 * [VerticalDivider] the fixed layout used, inside a wider transparent hit area so it's easy to grab, and
 * shows a horizontal-resize cursor on hover.
 *
 * [onResize] receives the drag delta already converted from pixels to **dp** (matching the dp-based
 * panel widths), positive-rightward; the caller decides whether that grows or shrinks its panel.
 * [onCommit] fires once, when the drag ends, so persistence happens per-gesture, not per-frame.
 */
@Composable
internal fun ResizableDivider(onResize: (Float) -> Unit, onCommit: () -> Unit) {
    val density = LocalDensity.current.density
    Box(
        Modifier
            .fillMaxHeight()
            .width(7.dp)
            .pointerHoverIcon(RESIZE_CURSOR)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { deltaPx -> onResize(deltaPx / density) },
                onDragStopped = { onCommit() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider()
    }
}

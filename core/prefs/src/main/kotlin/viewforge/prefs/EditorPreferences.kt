package viewforge.prefs

import kotlinx.serialization.Serializable

/**
 * The persisted editor preferences (ADR-023) — per-user *chrome*, never document data. It has its own
 * [prefsVersion], independent of the `.vforge` `schemaVersion`: editor layout is not project data, so
 * it must never travel in a project file and does not share the document's migration chain.
 *
 * Holds the [panelLayout] (S1), the [autosaveIntervalSeconds] (S5, #55), the [recentProjects] list
 * (D8, #88), the [chromeDark] editor-theme flag (S3, #104), and the S5 [historyDepth]/[defaultExportPath]
 * settings (#105); window geometry is the natural next tenant of this same file.
 *
 * @property autosaveIntervalSeconds how often crash-recovery autosave writes its sidecar while there
 *   are unsaved edits (D4, #54). Clamped on load to a sane range so a hand-edited value can neither
 *   hammer the disk nor effectively disable recovery.
 * @property recentProjects recently opened/saved `.vforge` paths, most-recent first (D8). Absolute paths
 *   are fine here — `preferences.json` is per-user config that is never committed, unlike a `.vforge`
 *   file (PR-4). De-duplicated and capped on load via [RecentProjects.sanitized].
 * @property chromeDark whether the editor *chrome* (panels, menus, toolbar) uses the dark color scheme
 *   (S3, #104). This is the editor's own theme, wholly independent of the project preview's light/dark
 *   (`EditorState.canvasDark`, H2). Defaults to `true` so an upgrading user keeps the previously
 *   hardcoded-dark chrome, and a prefs file predating this field falls back to dark (forward tolerance).
 * @property historyDepth how many undo entries the editor keeps (S5, #105). Clamped on load so a
 *   hand-edited value can neither wedge undo off nor grow memory without bound. Defaults to the value the
 *   undo history [core/command `History`] shipped hardcoded, so an upgrading user sees no change.
 * @property defaultExportPath the directory an Export dialog opens in by default (S5, #105); blank means
 *   "no default — let the picker start wherever the OS chooses". A per-user config path, never a `.vforge`.
 * @property favoriteComponents palette entries the user has pinned (P5a, #121), by stable key
 *   (`componentId ?: type`) in the order they were starred. Built-in keys are stable across projects; a
 *   user-component key only resolves in its own document and is otherwise ignored, so the raw list is safe
 *   to persist. De-duplicated and blank-dropped on load via [FavoriteComponents.sanitized].
 */
@Serializable
data class EditorPreferences(
    val prefsVersion: Int = CURRENT_VERSION,
    val panelLayout: PanelLayout = PanelLayout(),
    val autosaveIntervalSeconds: Int = DEFAULT_AUTOSAVE_INTERVAL_SECONDS,
    val recentProjects: List<String> = emptyList(),
    val chromeDark: Boolean = DEFAULT_CHROME_DARK,
    val historyDepth: Int = DEFAULT_HISTORY_DEPTH,
    val defaultExportPath: String = "",
    val favoriteComponents: List<String> = emptyList(),
) {
    /** Clamp any out-of-range values a hand-edited or older/newer file might carry (see [PanelLayout.sanitized]). */
    fun sanitized(): EditorPreferences = copy(
        panelLayout = panelLayout.sanitized(),
        autosaveIntervalSeconds = clampAutosaveInterval(autosaveIntervalSeconds),
        recentProjects = RecentProjects.sanitized(recentProjects),
        historyDepth = clampHistoryDepth(historyDepth),
        favoriteComponents = FavoriteComponents.sanitized(favoriteComponents),
    )

    companion object {
        /** Bump only for a change this build cannot read forward-tolerantly (unknown keys are already ignored). */
        const val CURRENT_VERSION = 1

        /** Matches the interval #54 shipped hardcoded, so an upgrading user sees no behaviour change. */
        const val DEFAULT_AUTOSAVE_INTERVAL_SECONDS = 10

        /** Matches the chrome #19 shipped hardcoded-dark, so an upgrading user sees no theme change (S3). */
        const val DEFAULT_CHROME_DARK = true
        const val MIN_AUTOSAVE_INTERVAL_SECONDS = 2
        const val MAX_AUTOSAVE_INTERVAL_SECONDS = 600

        /** Matches `History.DEFAULT_LIMIT`, so an upgrading user's undo depth is unchanged (S5). */
        const val DEFAULT_HISTORY_DEPTH = 200
        const val MIN_HISTORY_DEPTH = 10
        const val MAX_HISTORY_DEPTH = 1000

        /** The one place the autosave interval is bounded — used on load and by any future editor UI. */
        fun clampAutosaveInterval(seconds: Int): Int =
            seconds.coerceIn(MIN_AUTOSAVE_INTERVAL_SECONDS, MAX_AUTOSAVE_INTERVAL_SECONDS)

        /** The one place the history depth is bounded — used on load and by the Preferences dialog (S5). */
        fun clampHistoryDepth(entries: Int): Int = entries.coerceIn(MIN_HISTORY_DEPTH, MAX_HISTORY_DEPTH)
    }
}

/**
 * Pure operations on the recent-projects list (D8, #88): keep it most-recent-first, de-duplicated, and
 * capped. Shared by the persisted [EditorPreferences] and the editor's live in-memory copy so both agree
 * on the ordering and the cap — the list the menu shows is exactly the list that is saved.
 */
object RecentProjects {
    const val MAX = 10

    /** [path] promoted to the front, any earlier occurrence removed, then capped to [max]. */
    fun updated(current: List<String>, path: String, max: Int = MAX): List<String> =
        (listOf(path) + current.filterNot { it == path }).take(max)

    /** Drop blanks and duplicates and cap — applied on load so a hand-edited file can't wedge the list. */
    fun sanitized(list: List<String>, max: Int = MAX): List<String> =
        list.filter { it.isNotBlank() }.distinct().take(max)
}

/**
 * Pure operations on the palette favorites list (P5a, #121): a set of pinned palette keys kept in the
 * order they were starred. Shared by the persisted [EditorPreferences] and the editor's live copy so the
 * pinned rows the palette shows are exactly what is saved. Uncapped — favorites are explicit user choices,
 * not an automatic history like [RecentProjects].
 */
object FavoriteComponents {
    /** Toggle membership of [key]: remove it if already pinned, else append it (preserving starring order). */
    fun toggled(current: List<String>, key: String): List<String> =
        if (key in current) current.filterNot { it == key } else current + key

    /** Drop blanks and duplicates — applied on load so a hand-edited file can't carry junk. */
    fun sanitized(list: List<String>): List<String> = list.filter { it.isNotBlank() }.distinct()
}

/**
 * The panel layout (S1, issue #43): which panels are shown, and how wide each is (in dp). Widths are
 * plain [Float] magnitudes rather than a Compose `Dp` so this stays Compose-free; the shell attaches
 * `.dp`. The canvas has no entry — it is always visible and takes the remaining space.
 *
 * The three side panels default to visible; the code-preview panel (G3, #50) defaults to *hidden* and
 * is wider, matching how it shipped transient before this became persisted (#52). A file predating #52
 * simply omits the code-preview fields, so they fall back to those defaults (forward tolerance).
 *
 * The code-preview panel has its **own larger width bound** ([MAX_CODE_PREVIEW_WIDTH]) and an optional
 * [codePreviewWrap] soft-wrap flag (#115): it shows source, whose lines are far longer than a side panel's
 * labels, so the side-panel [MAX_WIDTH] was too small to ever see a full-width line without wrapping.
 */
@Serializable
data class PanelLayout(
    val paletteVisible: Boolean = true,
    val treeVisible: Boolean = true,
    val inspectorVisible: Boolean = true,
    val codePreviewVisible: Boolean = false,
    val paletteWidth: Float = DEFAULT_PALETTE_WIDTH,
    val treeWidth: Float = DEFAULT_TREE_WIDTH,
    val inspectorWidth: Float = DEFAULT_INSPECTOR_WIDTH,
    val codePreviewWidth: Float = DEFAULT_CODE_PREVIEW_WIDTH,
    val codePreviewWrap: Boolean = false,
) {
    /**
     * Force every width back into its bound. Applied on load so a corrupt, hand-edited, or
     * differently-versioned file can never wedge a panel to zero or off-screen. The side panels use
     * [clampWidth]; the code preview uses its wider [clampCodePreviewWidth]. Both are the single clamps the
     * shell's resize also uses, so the store and the live drag agree.
     */
    fun sanitized(): PanelLayout = copy(
        paletteWidth = clampWidth(paletteWidth),
        treeWidth = clampWidth(treeWidth),
        inspectorWidth = clampWidth(inspectorWidth),
        codePreviewWidth = clampCodePreviewWidth(codePreviewWidth),
    )

    companion object {
        const val MIN_WIDTH = 140f
        const val MAX_WIDTH = 520f

        /** The code preview can be dragged far wider than a side panel, since it shows full-width source (#115). */
        const val MAX_CODE_PREVIEW_WIDTH = 900f

        const val DEFAULT_PALETTE_WIDTH = 180f
        const val DEFAULT_TREE_WIDTH = 210f
        const val DEFAULT_INSPECTOR_WIDTH = 240f

        /** The code preview shows source, so it defaults wider than the side panels (matches #50). */
        const val DEFAULT_CODE_PREVIEW_WIDTH = 340f

        /** The one place a side-panel width is bounded — reused by both the store and the live resize drag. */
        fun clampWidth(dp: Float): Float = dp.coerceIn(MIN_WIDTH, MAX_WIDTH)

        /** The one place the code-preview width is bounded (its own wider max, #115). */
        fun clampCodePreviewWidth(dp: Float): Float = dp.coerceIn(MIN_WIDTH, MAX_CODE_PREVIEW_WIDTH)
    }
}

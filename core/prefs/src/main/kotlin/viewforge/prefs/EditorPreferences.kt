package viewforge.prefs

import kotlinx.serialization.Serializable

/**
 * The persisted editor preferences (ADR-023) — per-user *chrome*, never document data. It has its own
 * [prefsVersion], independent of the `.vforge` `schemaVersion`: editor layout is not project data, so
 * it must never travel in a project file and does not share the document's migration chain.
 *
 * Holds the [panelLayout] (S1) and the [autosaveIntervalSeconds] (S5, #55); window size, recent files,
 * and the default export path are the natural next tenants of this same file.
 *
 * @property autosaveIntervalSeconds how often crash-recovery autosave writes its sidecar while there
 *   are unsaved edits (D4, #54). Clamped on load to a sane range so a hand-edited value can neither
 *   hammer the disk nor effectively disable recovery.
 */
@Serializable
data class EditorPreferences(
    val prefsVersion: Int = CURRENT_VERSION,
    val panelLayout: PanelLayout = PanelLayout(),
    val autosaveIntervalSeconds: Int = DEFAULT_AUTOSAVE_INTERVAL_SECONDS,
) {
    /** Clamp any out-of-range values a hand-edited or older/newer file might carry (see [PanelLayout.sanitized]). */
    fun sanitized(): EditorPreferences = copy(
        panelLayout = panelLayout.sanitized(),
        autosaveIntervalSeconds = clampAutosaveInterval(autosaveIntervalSeconds),
    )

    companion object {
        /** Bump only for a change this build cannot read forward-tolerantly (unknown keys are already ignored). */
        const val CURRENT_VERSION = 1

        /** Matches the interval #54 shipped hardcoded, so an upgrading user sees no behaviour change. */
        const val DEFAULT_AUTOSAVE_INTERVAL_SECONDS = 10
        const val MIN_AUTOSAVE_INTERVAL_SECONDS = 2
        const val MAX_AUTOSAVE_INTERVAL_SECONDS = 600

        /** The one place the autosave interval is bounded — used on load and by any future editor UI. */
        fun clampAutosaveInterval(seconds: Int): Int =
            seconds.coerceIn(MIN_AUTOSAVE_INTERVAL_SECONDS, MAX_AUTOSAVE_INTERVAL_SECONDS)
    }
}

/**
 * The panel layout (S1, issue #43): which panels are shown, and how wide each is (in dp). Widths are
 * plain [Float] magnitudes rather than a Compose `Dp` so this stays Compose-free; the shell attaches
 * `.dp`. The canvas has no entry — it is always visible and takes the remaining space.
 *
 * The three side panels default to visible; the code-preview panel (G3, #50) defaults to *hidden* and
 * is wider, matching how it shipped transient before this became persisted (#52). A file predating #52
 * simply omits the code-preview fields, so they fall back to those defaults (forward tolerance).
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
) {
    /**
     * Force every width back into [[MIN_WIDTH], [MAX_WIDTH]]. Applied on load so a corrupt, hand-edited,
     * or differently-versioned file can never wedge a panel to zero or off-screen. [clampWidth] is the
     * single clamp the shell's resize also uses, so the store and the live drag agree.
     */
    fun sanitized(): PanelLayout = copy(
        paletteWidth = clampWidth(paletteWidth),
        treeWidth = clampWidth(treeWidth),
        inspectorWidth = clampWidth(inspectorWidth),
        codePreviewWidth = clampWidth(codePreviewWidth),
    )

    companion object {
        const val MIN_WIDTH = 140f
        const val MAX_WIDTH = 520f

        const val DEFAULT_PALETTE_WIDTH = 180f
        const val DEFAULT_TREE_WIDTH = 210f
        const val DEFAULT_INSPECTOR_WIDTH = 240f

        /** The code preview shows source, so it defaults wider than the side panels (matches #50). */
        const val DEFAULT_CODE_PREVIEW_WIDTH = 340f

        /** The one place a panel width is bounded — reused by both the store and the live resize drag. */
        fun clampWidth(dp: Float): Float = dp.coerceIn(MIN_WIDTH, MAX_WIDTH)
    }
}

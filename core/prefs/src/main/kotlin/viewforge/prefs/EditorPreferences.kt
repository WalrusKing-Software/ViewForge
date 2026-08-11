package viewforge.prefs

import kotlinx.serialization.Serializable

/**
 * The persisted editor preferences (ADR-023) — per-user *chrome*, never document data. It has its own
 * [prefsVersion], independent of the `.vforge` `schemaVersion`: editor layout is not project data, so
 * it must never travel in a project file and does not share the document's migration chain.
 *
 * Phase 1 holds only the [panelLayout]; window size, recent files, and the S5 preferences (autosave
 * interval, default export path) are the natural next tenants of this same file.
 */
@Serializable
data class EditorPreferences(val prefsVersion: Int = CURRENT_VERSION, val panelLayout: PanelLayout = PanelLayout()) {
    /** Clamp any out-of-range values a hand-edited or older/newer file might carry (see [PanelLayout.sanitized]). */
    fun sanitized(): EditorPreferences = copy(panelLayout = panelLayout.sanitized())

    companion object {
        /** Bump only for a change this build cannot read forward-tolerantly (unknown keys are already ignored). */
        const val CURRENT_VERSION = 1
    }
}

/**
 * The side-panel layout (S1, issue #43): which of the three panels are shown, and how wide each is (in
 * dp). Widths are plain [Float] magnitudes rather than a Compose `Dp` so this stays Compose-free; the
 * shell attaches `.dp`. The canvas has no entry — it is always visible and takes the remaining space.
 */
@Serializable
data class PanelLayout(
    val paletteVisible: Boolean = true,
    val treeVisible: Boolean = true,
    val inspectorVisible: Boolean = true,
    val paletteWidth: Float = DEFAULT_PALETTE_WIDTH,
    val treeWidth: Float = DEFAULT_TREE_WIDTH,
    val inspectorWidth: Float = DEFAULT_INSPECTOR_WIDTH,
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
    )

    companion object {
        const val MIN_WIDTH = 140f
        const val MAX_WIDTH = 520f

        const val DEFAULT_PALETTE_WIDTH = 180f
        const val DEFAULT_TREE_WIDTH = 210f
        const val DEFAULT_INSPECTOR_WIDTH = 240f

        /** The one place a panel width is bounded — reused by both the store and the live resize drag. */
        fun clampWidth(dp: Float): Float = dp.coerceIn(MIN_WIDTH, MAX_WIDTH)
    }
}

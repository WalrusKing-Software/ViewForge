package viewforge.editor.state

/**
 * The canvas's zoom and pan, as a pure, Compose-free value type (FEATURES C5). It is transient view
 * state — the editor's window onto the rendered screen, never part of the document, never serialized.
 *
 * Kept a plain data class (floats, not `Offset`) so the clamping and step math is unit-testable
 * without a composition, the same way `hitTest` and `editMenuModel` are pure. The canvas turns this
 * into the single `graphicsLayer` that scales/translates the rendered content; because that is the one
 * canonical transform (TECHNICAL_NOTES §5), hit-testing stays correct at every zoom/pan level without
 * any coordinate math at the call sites.
 *
 * [zoom] is a multiplier (1f = 100%), clamped to [[MIN_ZOOM], [MAX_ZOOM]]. [panX]/[panY] are a
 * window-space translation in pixels.
 */
data class CanvasViewport(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    /** Whether another zoom-in/out step would still change the zoom (false at the clamp bounds). */
    val canZoomIn: Boolean get() = zoom < MAX_ZOOM
    val canZoomOut: Boolean get() = zoom > MIN_ZOOM

    /** Multiply the zoom by [factor] (about the viewport centre — pan is unchanged), clamped. */
    fun zoomedBy(factor: Float): CanvasViewport = copy(zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM))

    /** One zoom-in step ([ZOOM_STEP]). */
    fun zoomedIn(): CanvasViewport = zoomedBy(ZOOM_STEP)

    /** One zoom-out step. */
    fun zoomedOut(): CanvasViewport = zoomedBy(1f / ZOOM_STEP)

    /** Translate by a window-space delta (the 1:1 cursor-follow pan). */
    fun pannedBy(dx: Float, dy: Float): CanvasViewport = copy(panX = panX + dx, panY = panY + dy)

    /** Back to 100% at the origin (View → Reset Zoom). */
    fun reset(): CanvasViewport = CanvasViewport()

    companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 4f

        /** Per-step zoom multiplier for the menu items and keyboard shortcuts (~20% per press). */
        const val ZOOM_STEP = 1.2f
    }
}

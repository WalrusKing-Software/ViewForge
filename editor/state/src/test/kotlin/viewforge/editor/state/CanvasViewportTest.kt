package viewforge.editor.state

import viewforge.editor.state.CanvasViewport.Companion.MAX_ZOOM
import viewforge.editor.state.CanvasViewport.Companion.MIN_ZOOM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure zoom/pan math (C5). This is the "test hit-testing at multiple zoom levels" contract of
 * TECHNICAL_NOTES §5 made unit-testable: the canvas realises exactly this value as its single
 * transform, so pinning the clamping and stepping here pins the transform the overlay reconciles
 * against. No composition or framework needed.
 */
class CanvasViewportTest {
    @Test
    fun `defaults to 100 percent at the origin`() {
        val v = CanvasViewport()
        assertEquals(1f, v.zoom)
        assertEquals(0f, v.panX)
        assertEquals(0f, v.panY)
    }

    @Test
    fun `zoom in and out step by the multiplier`() {
        val v = CanvasViewport()
        assertEquals(CanvasViewport.ZOOM_STEP, v.zoomedIn().zoom)
        assertEquals(1f / CanvasViewport.ZOOM_STEP, v.zoomedOut().zoom)
    }

    @Test
    fun `zoom clamps to the allowed range`() {
        // Hammer well past both bounds; the zoom saturates rather than running away.
        var zoomedIn = CanvasViewport()
        repeat(50) { zoomedIn = zoomedIn.zoomedIn() }
        assertEquals(MAX_ZOOM, zoomedIn.zoom)

        var zoomedOut = CanvasViewport()
        repeat(50) { zoomedOut = zoomedOut.zoomedOut() }
        assertEquals(MIN_ZOOM, zoomedOut.zoom)
    }

    @Test
    fun `zoomedBy multiplies and clamps`() {
        assertEquals(2f, CanvasViewport().zoomedBy(2f).zoom)
        assertEquals(MAX_ZOOM, CanvasViewport().zoomedBy(100f).zoom)
        assertEquals(MIN_ZOOM, CanvasViewport().zoomedBy(0.001f).zoom)
    }

    @Test
    fun `canZoomIn and canZoomOut report the clamp bounds`() {
        val mid = CanvasViewport()
        assertTrue(mid.canZoomIn)
        assertTrue(mid.canZoomOut)

        val maxed = CanvasViewport(zoom = MAX_ZOOM)
        assertFalse(maxed.canZoomIn)
        assertTrue(maxed.canZoomOut)

        val minned = CanvasViewport(zoom = MIN_ZOOM)
        assertTrue(minned.canZoomIn)
        assertFalse(minned.canZoomOut)
    }

    @Test
    fun `pan accumulates window-space deltas`() {
        val v = CanvasViewport().pannedBy(10f, -5f).pannedBy(2f, 3f)
        assertEquals(12f, v.panX)
        assertEquals(-2f, v.panY)
    }

    @Test
    fun `reset returns to the default view`() {
        val moved = CanvasViewport(zoom = 3f, panX = 40f, panY = 12f)
        assertEquals(CanvasViewport(), moved.reset())
    }

    @Test
    fun `fittedTo scales by the tighter axis`() {
        // Content twice as wide as the window but the same height: width is the binding axis (0.5).
        assertEquals(0.5f, CanvasViewport().fittedTo(800f, 600f, 1600f, 600f).zoom)
        // Content twice as tall: height binds (0.5).
        assertEquals(0.5f, CanvasViewport().fittedTo(800f, 600f, 800f, 1200f).zoom)
    }

    @Test
    fun `fittedTo clamps to the zoom range`() {
        // Tiny content would want a huge zoom; saturates at the max.
        assertEquals(MAX_ZOOM, CanvasViewport().fittedTo(8000f, 8000f, 100f, 100f).zoom)
        // Huge content would want a minuscule zoom; saturates at the min (still clipped, but navigable).
        assertEquals(MIN_ZOOM, CanvasViewport().fittedTo(100f, 100f, 8000f, 8000f).zoom)
    }

    @Test
    fun `fittedTo recentres the frame`() {
        // Whatever the prior pan/zoom, a fit resets pan to the origin so the centred frame stays centred.
        val fitted = CanvasViewport(zoom = 3f, panX = 40f, panY = 12f).fittedTo(800f, 800f, 800f, 800f)
        assertEquals(CanvasViewport(zoom = 1f), fitted)
    }

    @Test
    fun `fittedTo is a no-op before the canvas is measured`() {
        val v = CanvasViewport(zoom = 2f, panX = 5f)
        assertEquals(v, v.fittedTo(0f, 600f, 800f, 600f))
        assertEquals(v, v.fittedTo(800f, 600f, 0f, 600f))
    }
}

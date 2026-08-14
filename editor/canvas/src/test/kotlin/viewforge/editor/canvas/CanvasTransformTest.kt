package viewforge.editor.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import viewforge.editor.state.CanvasViewport
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one canonical content↔screen transform (#116). Node bounds are stored in the frame's unscaled
 * content space; these pure functions apply the live zoom/pan so outlines and hit-testing stay aligned.
 * The frame is centre-anchored and scales about its centre, so the maths is verified here without a
 * composition — the visual behaviour is a run-the-app check.
 */
class CanvasTransformTest {
    private val overlay = Size(200f, 200f)
    private val frame = Size(100f, 100f) // a 100px frame centred in a 200px overlay
    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 1e-3f)
        assertEquals(expected.y, actual.y, 1e-3f)
    }

    @Test
    fun `at zoom 1 pan 0 a frame point maps to its centred screen position`() {
        val vp = CanvasViewport(zoom = 1f)
        // The frame's top-left (content 0,0) sits at the overlay centre minus half the frame.
        assertOffset(Offset(50f, 50f), contentToScreen(Offset(0f, 0f), overlay, frame, vp))
        // The frame centre maps to the overlay centre (the scale origin).
        assertOffset(Offset(100f, 100f), contentToScreen(Offset(50f, 50f), overlay, frame, vp))
    }

    @Test
    fun `zoom scales about the frame centre`() {
        val vp = CanvasViewport(zoom = 2f)
        // The centre is fixed under scaling.
        assertOffset(Offset(100f, 100f), contentToScreen(Offset(50f, 50f), overlay, frame, vp))
        // A point 10px right of centre moves to 20px right of centre at 2x.
        assertOffset(Offset(120f, 100f), contentToScreen(Offset(60f, 50f), overlay, frame, vp))
    }

    @Test
    fun `pan translates in screen pixels after the scale`() {
        val vp = CanvasViewport(zoom = 1f, panX = 5f, panY = 7f)
        assertOffset(Offset(105f, 107f), contentToScreen(Offset(50f, 50f), overlay, frame, vp))
    }

    @Test
    fun `screenToContent is the inverse of contentToScreen`() {
        val vp = CanvasViewport(zoom = 1.75f, panX = 13f, panY = -9f)
        val content = Offset(37f, 88f)
        val roundTrip = screenToContent(contentToScreen(content, overlay, frame, vp), overlay, frame, vp)
        assertOffset(content, roundTrip)
    }

    @Test
    fun `a content rect maps its top-left through the transform and scales its size by zoom`() {
        val vp = CanvasViewport(zoom = 2f)
        val screen = contentRectToScreen(Rect(Offset(50f, 50f), Size(20f, 30f)), overlay, frame, vp)
        assertOffset(Offset(100f, 100f), screen.topLeft) // top-left at the frame centre stays at overlay centre
        assertEquals(40f, screen.width, 1e-3f)
        assertEquals(60f, screen.height, 1e-3f)
    }
}

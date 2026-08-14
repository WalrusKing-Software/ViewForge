package viewforge.editor.canvas

import androidx.compose.ui.geometry.Rect
import viewforge.model.Node
import viewforge.model.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure measure/spacing geometry (C12, #119) — no Compose UI harness. A child sits inside a parent
 * container; bounds are supplied directly, mirroring what the editor instrumentation records.
 */
class MeasureGapsTest {
    private val child = Node(id = NodeId("child"), type = "compose.material3.Text")
    private val root =
        Node(id = NodeId("root"), type = "compose.foundation.layout.Box", children = listOf(child))

    // parent fills 0..100 x 0..100; child sits at 20..60 x 30..70, fully inside.
    private val rects =
        mapOf(
            "root" to Rect(0f, 0f, 100f, 100f),
            "child" to Rect(20f, 30f, 60f, 70f),
        )

    @Test
    fun `measures the four gaps to the parent edges with the right distances and axes`() {
        val segs = measureGaps(rects, root, NodeId("child"))
        assertEquals(4, segs.size)
        // left / right run horizontally at the child's vertical centre (50); top / bottom vertically at x=40.
        val left = segs.single { it.axis == MeasureAxis.Horizontal && it.distance == 20f }
        assertEquals(50f, left.start.y)
        assertEquals(0f, left.start.x)
        assertEquals(20f, left.end.x)
        assertTrue(segs.any { it.axis == MeasureAxis.Horizontal && it.distance == 40f }) // right
        assertTrue(segs.any { it.axis == MeasureAxis.Vertical && it.distance == 30f }) // top and bottom (both 30)
        assertEquals(setOf(20f, 40f, 30f), segs.map { it.distance }.toSet())
    }

    @Test
    fun `the root has no parent, so nothing to measure`() {
        assertEquals(emptyList(), measureGaps(rects, root, NodeId("root")))
    }

    @Test
    fun `a node with no recorded bounds yields nothing`() {
        assertEquals(emptyList(), measureGaps(mapOf("root" to Rect(0f, 0f, 100f, 100f)), root, NodeId("child")))
    }

    @Test
    fun `a side where the node overflows the parent is dropped, not drawn backwards`() {
        // Child pokes past the parent's left edge: that gap is negative and must be filtered out.
        val overflow = rects + ("child" to Rect(-10f, 30f, 60f, 70f))
        val segs = measureGaps(overflow, root, NodeId("child"))
        assertEquals(3, segs.size)
        assertTrue(segs.none { it.axis == MeasureAxis.Horizontal && it.start.x == 0f && it.end.x == -10f })
    }
}

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

    // A parent with two children: `sel` in the middle and a `left` sibling to its left, overlapping it
    // vertically. Measuring `sel` should snap the left gap to the sibling's edge, not the parent's (#127).
    private val leftSibling = Node(id = NodeId("left"), type = "compose.material3.Text")
    private val sel = Node(id = NodeId("sel"), type = "compose.material3.Text")
    private val rowRoot =
        Node(
            id = NodeId("root"),
            type = "compose.foundation.layout.Row",
            children = listOf(leftSibling, sel),
        )

    @Test
    fun `the left gap measures to the nearest overlapping sibling, other sides to the parent`() {
        // parent 0..100 x 0..100; left sibling 0..30 (right edge 30), sel 50..70 x 30..70.
        val r =
            mapOf(
                "root" to Rect(0f, 0f, 100f, 100f),
                "left" to Rect(0f, 30f, 30f, 70f),
                "sel" to Rect(50f, 30f, 70f, 70f),
            )
        val segs = measureGaps(r, rowRoot, NodeId("sel"))
        // Left gap: sel.left(50) - sibling.right(30) = 20, starting at the sibling edge (x=30), not x=0.
        val left = segs.single { it.axis == MeasureAxis.Horizontal && it.end.x == 50f }
        assertEquals(20f, left.distance)
        assertEquals(30f, left.start.x)
        // Right side has no sibling → falls back to the parent edge (100 - 70 = 30).
        val right = segs.single { it.axis == MeasureAxis.Horizontal && it.start.x == 70f }
        assertEquals(30f, right.distance)
        assertEquals(100f, right.end.x)
    }

    @Test
    fun `a sibling that does not overlap on the perpendicular axis is ignored`() {
        // The sibling sits to the left but entirely above sel's vertical span → not across the left gap,
        // so the left gap still measures to the parent edge (sel.left 50 - parent.left 0 = 50).
        val r =
            mapOf(
                "root" to Rect(0f, 0f, 100f, 100f),
                "left" to Rect(0f, 0f, 30f, 20f), // sits at y 0..20, sel at y 30..70 → no vertical overlap
                "sel" to Rect(50f, 30f, 70f, 70f),
            )
        val left = measureGaps(r, rowRoot, NodeId("sel")).single {
            it.axis == MeasureAxis.Horizontal && it.end.x == 50f
        }
        assertEquals(50f, left.distance)
        assertEquals(0f, left.start.x)
    }

    @Test
    fun `when two siblings sit on the same side the nearest one wins`() {
        val nearer = Node(id = NodeId("near"), type = "compose.material3.Text")
        val farther = Node(id = NodeId("far"), type = "compose.material3.Text")
        val threeRoot =
            Node(
                id = NodeId("root"),
                type = "compose.foundation.layout.Row",
                children = listOf(farther, nearer, sel),
            )
        val r =
            mapOf(
                "root" to Rect(0f, 0f, 100f, 100f),
                "far" to Rect(0f, 30f, 10f, 70f), // right edge 10
                "near" to Rect(20f, 30f, 40f, 70f), // right edge 40 → closer to sel.left
                "sel" to Rect(50f, 30f, 70f, 70f),
            )
        val left = measureGaps(r, threeRoot, NodeId("sel")).single {
            it.axis == MeasureAxis.Horizontal &&
                it.end.x == 50f
        }
        assertEquals(10f, left.distance) // 50 - 40, the nearer sibling
        assertEquals(40f, left.start.x)
    }

    @Test
    fun `a sibling above snaps the top gap on the vertical axis`() {
        val above = Node(id = NodeId("above"), type = "compose.material3.Text")
        val colRoot =
            Node(
                id = NodeId("root"),
                type = "compose.foundation.layout.Column",
                children = listOf(above, sel),
            )
        val r =
            mapOf(
                "root" to Rect(0f, 0f, 100f, 100f),
                "above" to Rect(50f, 0f, 70f, 20f), // bottom edge 20, overlaps sel horizontally
                "sel" to Rect(50f, 40f, 70f, 60f),
            )
        val top = measureGaps(r, colRoot, NodeId("sel")).single { it.axis == MeasureAxis.Vertical && it.end.y == 40f }
        assertEquals(20f, top.distance) // sel.top 40 - sibling.bottom 20
        assertEquals(20f, top.start.y)
    }
}

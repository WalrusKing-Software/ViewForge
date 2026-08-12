package viewforge.editor.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import viewforge.model.ChildAddress
import viewforge.model.Node
import viewforge.model.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure canvas drop geometry (no Compose UI harness), the C7 counterpart of [HitTestTest]. Bounds are
 * supplied directly, mirroring what the editor instrumentation records in window space. Containers are
 * the `compose.foundation.layout.*` types; everything else is a leaf that never accepts children.
 */
class CanvasDropTest {
    private val accepts: (String) -> Boolean = { it.startsWith("compose.foundation.layout.") }

    // A vertical Column of three Text leaves, stacked 0..300 in y.
    private val t1 = Node(NodeId("t1"), "compose.material3.Text")
    private val t2 = Node(NodeId("t2"), "compose.material3.Text")
    private val t3 = Node(NodeId("t3"), "compose.material3.Text")
    private val col = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(t1, t2, t3))
    private val colRects = mapOf(
        "col" to Rect(0f, 0f, 100f, 300f),
        "t1" to Rect(0f, 0f, 100f, 100f), // centre y = 50
        "t2" to Rect(0f, 100f, 100f, 200f), // centre y = 150
        "t3" to Rect(0f, 200f, 100f, 300f), // centre y = 250
    )

    // --- canvasDropTarget -------------------------------------------------------------------------

    @Test
    fun `targets the container when the point is over its leaf children`() {
        assertEquals(NodeId("col"), canvasDropTarget(colRects, col, NodeId("none"), Offset(50f, 150f), accepts))
    }

    @Test
    fun `returns null over empty canvas`() {
        assertNull(canvasDropTarget(colRects, col, NodeId("none"), Offset(500f, 500f), accepts))
    }

    @Test
    fun `picks the deepest accepting container when nested`() {
        val box = Node(NodeId("box"), "compose.foundation.layout.Box", children = listOf(t2))
        val root = col.copy(children = listOf(t1, box, t3))
        val rects = colRects + mapOf("box" to Rect(0f, 100f, 100f, 200f))
        // Point inside the nested Box → drops INTO the Box, not the outer Column.
        assertEquals(NodeId("box"), canvasDropTarget(rects, root, NodeId("none"), Offset(50f, 150f), accepts))
    }

    @Test
    fun `never targets the dragged node or its subtree`() {
        val box = Node(NodeId("box"), "compose.foundation.layout.Box", children = listOf(t2))
        val root = col.copy(children = listOf(t1, box, t3))
        val rects = colRects + mapOf("box" to Rect(0f, 100f, 100f, 200f))
        // Dragging the Box, hovering inside it → the Box is excluded, so the drop falls to the Column.
        assertEquals(NodeId("col"), canvasDropTarget(rects, root, NodeId("box"), Offset(50f, 150f), accepts))
    }

    // --- insertionIndex ---------------------------------------------------------------------------

    private val vertical = listOf(colRects["t1"]!!, colRects["t2"]!!, colRects["t3"]!!)

    @Test
    fun `vertical index counts children whose centre is above the point`() {
        assertEquals(0, insertionIndex(vertical, Offset(50f, 10f)))
        assertEquals(1, insertionIndex(vertical, Offset(50f, 120f)))
        assertEquals(2, insertionIndex(vertical, Offset(50f, 200f)))
        assertEquals(3, insertionIndex(vertical, Offset(50f, 290f)))
    }

    @Test
    fun `horizontal arrangement is inferred and indexed on x`() {
        val row = listOf(
            Rect(0f, 0f, 100f, 50f), // centre x = 50
            Rect(100f, 0f, 200f, 50f), // centre x = 150
            Rect(200f, 0f, 300f, 50f), // centre x = 250
        )
        assertEquals(0, insertionIndex(row, Offset(10f, 25f)))
        assertEquals(1, insertionIndex(row, Offset(120f, 25f)))
        assertEquals(3, insertionIndex(row, Offset(290f, 25f)))
    }

    @Test
    fun `an empty region inserts at zero`() {
        assertEquals(0, insertionIndex(emptyList(), Offset(50f, 50f)))
    }

    @Test
    fun `axis inference falls back to vertical for fewer than two rects`() {
        assertTrue(isVerticalArrangement(emptyList()))
        assertTrue(isVerticalArrangement(listOf(Rect(0f, 0f, 100f, 50f))))
        assertTrue(isVerticalArrangement(vertical))
        assertFalse(isVerticalArrangement(listOf(Rect(0f, 0f, 100f, 50f), Rect(100f, 0f, 200f, 50f))))
    }

    // --- canvasDropAddress (target + index) -------------------------------------------------------

    @Test
    fun `resolves a full address for a drop between children`() {
        assertEquals(
            ChildAddress(NodeId("col"), null, 1),
            canvasDropAddress(colRects, col, NodeId("none"), Offset(50f, 120f), accepts),
        )
    }

    @Test
    fun `excludes the dragged node from the index so a same-parent reorder lands correctly`() {
        // Dragging t1 (removed first), hovering just below its old slot: the remaining children are
        // t2 (centre 150) and t3 (centre 250), so a point at y=120 inserts before both → index 0.
        assertEquals(
            ChildAddress(NodeId("col"), null, 0),
            canvasDropAddress(colRects, col, NodeId("t1"), Offset(50f, 120f), accepts),
        )
    }

    @Test
    fun `resolves to null when no accepting container is under the point`() {
        assertNull(canvasDropAddress(colRects, col, NodeId("none"), Offset(500f, 500f), accepts))
    }
}

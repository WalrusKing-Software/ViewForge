package viewforge.editor.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import viewforge.model.Node
import viewforge.model.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure hit-testing (no Compose UI harness). The tree is a big outer container with a small inner
 * node; bounds are supplied directly, mirroring what the editor instrumentation records.
 */
class HitTestTest {
    private val inner = Node(id = NodeId("inner"), type = "compose.material3.Text")
    private val outer =
        Node(
            id = NodeId("outer"),
            type = "compose.foundation.layout.Box",
            children = listOf(inner),
        )

    // outer fills 0..100 x 0..100; inner sits at 40..60 x 40..60, fully inside outer.
    private val rects =
        mapOf(
            "outer" to Rect(0f, 0f, 100f, 100f),
            "inner" to Rect(40f, 40f, 60f, 60f),
        )

    @Test
    fun `picks the deepest node when the point is over a child`() {
        assertEquals(NodeId("inner"), hitTest(rects, outer, Offset(50f, 50f)))
    }

    @Test
    fun `picks the parent when the point misses the child`() {
        assertEquals(NodeId("outer"), hitTest(rects, outer, Offset(10f, 10f)))
    }

    @Test
    fun `returns null when the point is outside everything`() {
        assertNull(hitTest(rects, outer, Offset(200f, 200f)))
    }

    @Test
    fun `a node with no recorded bounds is skipped`() {
        // Only the child has bounds; the point falls in the child, which still resolves.
        assertEquals(NodeId("inner"), hitTest(mapOf("inner" to Rect(40f, 40f, 60f, 60f)), outer, Offset(50f, 50f)))
        // A point outside the child finds nothing, since outer has no bounds recorded.
        assertNull(hitTest(mapOf("inner" to Rect(40f, 40f, 60f, 60f)), outer, Offset(10f, 10f)))
    }

    @Test
    fun `a hidden node is never selectable, nor are its descendants`() {
        val hiddenOuter = outer.copy(hidden = true)
        assertNull(hitTest(rects, hiddenOuter, Offset(50f, 50f)))
    }
}

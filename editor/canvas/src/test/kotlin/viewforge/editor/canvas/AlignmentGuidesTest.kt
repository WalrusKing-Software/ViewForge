package viewforge.editor.canvas

import androidx.compose.ui.geometry.Rect
import viewforge.model.Node
import viewforge.model.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure static-alignment-guide geometry (C11, #118) — no Compose UI harness. A selected node and a sibling
 * live in a parent container; bounds are supplied directly, mirroring what the editor instrumentation
 * records. Rects are chosen so the selected node aligns with the *sibling* but not the parent.
 */
class AlignmentGuidesTest {
    private val root =
        Node(
            id = NodeId("root"),
            type = "compose.foundation.layout.Box",
            children =
            listOf(
                Node(id = NodeId("S"), type = "compose.material3.Text"),
                Node(id = NodeId("B"), type = "compose.material3.Text"),
            ),
        )
    private val parent = "root" to Rect(0f, 0f, 200f, 200f)
    private val selected = "S" to Rect(10f, 10f, 30f, 30f) // x-lines 10/20/30, y-lines 10/20/30

    @Test
    fun `a shared left edge with a sibling yields one vertical guide spanning both`() {
        // B's left (10) matches S's left (10); B is taller, so the guide spans y 10..70.
        val rects = mapOf(parent, selected, "B" to Rect(10f, 50f, 90f, 70f))
        val guides = alignmentGuides(rects, root, NodeId("S"))
        assertEquals(1, guides.size)
        val g = guides.single()
        assertEquals(GuideOrientation.Vertical, g.orientation)
        assertEquals(10f, g.position)
        assertEquals(10f, g.start)
        assertEquals(70f, g.end)
    }

    @Test
    fun `a shared top edge with a sibling yields one horizontal guide spanning both`() {
        // B's top (10) matches S's top (10); B is wider, so the guide spans x 10..90.
        val rects = mapOf(parent, selected, "B" to Rect(50f, 10f, 90f, 25f))
        val guides = alignmentGuides(rects, root, NodeId("S"))
        assertEquals(1, guides.size)
        val g = guides.single()
        assertEquals(GuideOrientation.Horizontal, g.orientation)
        assertEquals(10f, g.position)
        assertEquals(10f, g.start)
        assertEquals(90f, g.end)
    }

    @Test
    fun `no shared edge or centre means no guides`() {
        val rects = mapOf(parent, selected, "B" to Rect(120f, 120f, 140f, 140f))
        assertTrue(alignmentGuides(rects, root, NodeId("S")).isEmpty())
    }

    @Test
    fun `the root has no parent or siblings, so no guides`() {
        val rects = mapOf(parent, selected, "B" to Rect(10f, 50f, 90f, 70f))
        assertTrue(alignmentGuides(rects, root, NodeId("root")).isEmpty())
    }

    @Test
    fun `a node with no recorded bounds yields nothing`() {
        assertTrue(alignmentGuides(mapOf(parent), root, NodeId("S")).isEmpty())
    }
}

package viewforge.editor.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Repeater
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #297: a `vforge.repeat` is spliced out of the render and its template children are re-keyed per row
 * (`name#0`, `name#1`), so the overlay/hit-test — which walk the IR — must reconcile those rendered ids back
 * to the single IR id. These pin that reconciliation (pure, no Compose UI harness), mirroring [HitTestTest].
 */
class RepeatOverlayTest {
    private val name = Node(id = NodeId("name"), type = "compose.material3.Text")
    private val rep = Node(id = NodeId("rep"), type = Repeater.TYPE, children = listOf(name))
    private val root = Node(id = NodeId("root"), type = "compose.foundation.layout.Column", children = listOf(rep))

    // Two rendered rows of the template's `name`; the repeat itself has no rendered element (spliced out).
    private val rects =
        mapOf(
            "name#0" to Rect(0f, 0f, 100f, 20f),
            "name#1" to Rect(0f, 30f, 100f, 50f),
        )

    @Test
    fun `rectsForNode maps an IR id to all its rendered copies`() {
        assertEquals(
            setOf(Rect(0f, 0f, 100f, 20f), Rect(0f, 30f, 100f, 50f)),
            rectsForNode(rects, NodeId("name")).toSet(),
        )
        // A different id that shares a prefix but not the `#` boundary is not a copy.
        assertEquals(emptyList(), rectsForNode(mapOf("name2#0" to Rect(0f, 0f, 1f, 1f)), NodeId("name")))
    }

    @Test
    fun `hitTest resolves a template child through any of its rows`() {
        assertEquals(NodeId("name"), hitTest(rects, root, Offset(50f, 10f))) // row 0
        assertEquals(NodeId("name"), hitTest(rects, root, Offset(50f, 40f))) // row 1
    }

    @Test
    fun `nodeOutlineRects lists each copy for a template child`() {
        assertEquals(
            setOf(Rect(0f, 0f, 100f, 20f), Rect(0f, 30f, 100f, 50f)),
            nodeOutlineRects(rects, name).toSet(),
        )
    }

    @Test
    fun `nodeOutlineRects unions the subtree for a repeat`() {
        assertEquals(listOf(Rect(0f, 0f, 100f, 50f)), nodeOutlineRects(rects, rep))
    }

    @Test
    fun `an ordinary node still outlines its single recorded rect`() {
        val box = Node(id = NodeId("box"), type = "compose.foundation.layout.Box")
        assertEquals(listOf(Rect(0f, 0f, 10f, 10f)), nodeOutlineRects(mapOf("box" to Rect(0f, 0f, 10f, 10f)), box))
    }

    @Test
    fun `marquee selects a template child when every row is enclosed`() {
        assertEquals(listOf(NodeId("name")), marqueeSelection(rects, root, Rect(0f, 0f, 200f, 200f)))
        // A sweep that encloses only one row leaves the template child unselected (not all copies inside).
        assertEquals(emptyList(), marqueeSelection(rects, root, Rect(0f, 0f, 200f, 25f)))
    }
}

package viewforge.editor.canvas

import androidx.compose.ui.geometry.Rect
import viewforge.model.Node
import viewforge.model.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure rubber-band selection (no Compose UI harness), the marquee counterpart of [HitTestTest]. Bounds
 * are supplied directly, mirroring what the editor instrumentation records; the tree is an outer frame
 * with two children, one of which nests a grandchild.
 */
class MarqueeTest {
    private val b1 = Node(id = NodeId("b1"), type = "compose.material3.Text")
    private val a = Node(id = NodeId("a"), type = "compose.material3.Text")
    private val b =
        Node(id = NodeId("b"), type = "compose.foundation.layout.Box", children = listOf(b1))
    private val root =
        Node(id = NodeId("root"), type = "compose.foundation.layout.Box", children = listOf(a, b))

    // root fills 0..100; a sits at 10..30, b at 40..90 with b1 nested at 50..60.
    private val rects =
        mapOf(
            "root" to Rect(0f, 0f, 100f, 100f),
            "a" to Rect(10f, 10f, 30f, 30f),
            "b" to Rect(40f, 40f, 90f, 90f),
            "b1" to Rect(50f, 50f, 60f, 60f),
        )

    @Test
    fun `a fully enclosed node is selected`() {
        assertEquals(listOf(NodeId("a")), marqueeSelection(rects, root, Rect(5f, 5f, 35f, 35f)))
    }

    @Test
    fun `a partially overlapped node is not selected`() {
        // The marquee clips a's right edge, so a is not fully enclosed.
        assertEquals(emptyList(), marqueeSelection(rects, root, Rect(5f, 5f, 20f, 35f)))
    }

    @Test
    fun `enclosing everything selects the top-level children, not the root or nested descendants`() {
        // Sweeping the whole frame takes a and b; b's child b1 is skipped (redundant), root is never taken.
        assertEquals(listOf(NodeId("a"), NodeId("b")), marqueeSelection(rects, root, Rect(0f, 0f, 100f, 100f)))
    }

    @Test
    fun `a locked node is skipped but its enclosed descendants remain eligible`() {
        val lockedTree = root.copy(children = listOf(a, b.copy(locked = true)))
        assertEquals(
            listOf(NodeId("a"), NodeId("b1")),
            marqueeSelection(rects, lockedTree, Rect(0f, 0f, 100f, 100f)),
        )
    }

    @Test
    fun `a hidden node and its whole subtree are skipped`() {
        val hiddenTree = root.copy(children = listOf(a, b.copy(hidden = true)))
        assertEquals(listOf(NodeId("a")), marqueeSelection(rects, hiddenTree, Rect(0f, 0f, 100f, 100f)))
    }

    @Test
    fun `a node with no recorded bounds is skipped`() {
        val noBBounds = mapOf("a" to Rect(10f, 10f, 30f, 30f))
        assertEquals(listOf(NodeId("a")), marqueeSelection(noBBounds, root, Rect(0f, 0f, 100f, 100f)))
    }

    @Test
    fun `an empty sweep selects nothing`() {
        assertEquals(emptyList(), marqueeSelection(rects, root, Rect(5f, 5f, 5f, 5f)))
    }

    // --- combineMarquee: how a sweep merges with the standing selection (#99) --------------------

    @Test
    fun `a plain sweep replaces the selection`() {
        assertEquals(
            listOf(NodeId("b")),
            combineMarquee(base = listOf(NodeId("a")), hits = listOf(NodeId("b")), additive = false),
        )
    }

    @Test
    fun `an additive sweep unions into the standing selection, hit stays primary`() {
        assertEquals(
            listOf(NodeId("a"), NodeId("b")),
            combineMarquee(base = listOf(NodeId("a")), hits = listOf(NodeId("b")), additive = true),
        )
    }

    @Test
    fun `an additive sweep drops a re-enclosed node so it is not listed twice and stays primary`() {
        // a is already selected and re-enclosed; it moves to the end (primary), not duplicated.
        assertEquals(
            listOf(NodeId("b"), NodeId("a")),
            combineMarquee(base = listOf(NodeId("b"), NodeId("a")), hits = listOf(NodeId("a")), additive = true),
        )
    }

    @Test
    fun `an additive empty sweep leaves the selection untouched`() {
        assertEquals(
            listOf(NodeId("a"), NodeId("b")),
            combineMarquee(base = listOf(NodeId("a"), NodeId("b")), hits = emptyList(), additive = true),
        )
    }
}

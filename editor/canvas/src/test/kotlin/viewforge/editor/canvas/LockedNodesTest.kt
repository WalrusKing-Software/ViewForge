package viewforge.editor.canvas

import viewforge.model.Node
import viewforge.model.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure [lockedNodes] walk behind the canvas lock indicator (T4). Compose-free, so it is verified
 * without a UI harness, like the other Selection.kt geometry helpers.
 */
class LockedNodesTest {
    private fun node(id: String, locked: Boolean = false, hidden: Boolean = false, children: List<Node> = emptyList()) =
        Node(NodeId(id), "compose.material3.Text", locked = locked, hidden = hidden, children = children)

    @Test
    fun `collects every locked node, in pre-order`() {
        val tree = node(
            "root",
            children = listOf(
                node("a", locked = true),
                node("b", children = listOf(node("b1", locked = true))),
            ),
        )
        assertEquals(listOf(NodeId("a"), NodeId("b1")), lockedNodes(tree).map { it.id })
    }

    @Test
    fun `a locked node nested under another locked node is still collected (per-node lock)`() {
        val tree = node("outer", locked = true, children = listOf(node("inner", locked = true)))
        assertEquals(listOf(NodeId("outer"), NodeId("inner")), lockedNodes(tree).map { it.id })
    }

    @Test
    fun `a hidden subtree is skipped even if it contains locked nodes`() {
        val tree =
            node("root", children = listOf(node("h", hidden = true, children = listOf(node("hc", locked = true)))))
        assertEquals(emptyList(), lockedNodes(tree).map { it.id })
    }

    @Test
    fun `no locked nodes yields an empty list`() {
        assertEquals(emptyList(), lockedNodes(node("root", children = listOf(node("a")))).map { it.id })
    }
}

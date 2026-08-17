package viewforge.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class NodeTreeTest {
    // A small tree exercising both a default-region child and a slotted child.
    private val leafInSlot = Node(id = NodeId("slot-leaf"), type = "compose.material3.Text")
    private val childA = Node(id = NodeId("a"), type = "compose.material3.Text")
    private val childB =
        Node(
            id = NodeId("b"),
            type = "compose.material3.Button",
            slots = mapOf("content" to listOf(leafInSlot)),
        )
    private val root =
        Node(
            id = NodeId("root"),
            type = "compose.foundation.layout.Column",
            children = listOf(childA, childB),
        )

    @Test
    fun `findById returns the node itself`() {
        assertSame(root, root.findById(NodeId("root")))
    }

    @Test
    fun `findById descends into children`() {
        assertSame(childA, root.findById(NodeId("a")))
    }

    @Test
    fun `findById descends into slots`() {
        assertSame(leafInSlot, root.findById(NodeId("slot-leaf")))
    }

    @Test
    fun `findById returns null for an absent id`() {
        assertNull(root.findById(NodeId("nope")))
    }

    @Test
    fun `allChildren lists a node's own default children then its slot children`() {
        // root's direct children are childA and childB; leafInSlot belongs to childB's slot, not root.
        assertEquals(listOf(childA, childB), root.allChildren())
        // childB contributes no default children, only its slotted content.
        assertEquals(listOf(leafInSlot), childB.allChildren())
    }
}

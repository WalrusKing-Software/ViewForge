package viewforge.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NodeEditTest {
    // root
    // ├─ a (Text)
    // └─ b (Button)
    //     └─ slot "content": [ leaf (Text) ]
    private val leaf = Node(id = NodeId("leaf"), type = "compose.material3.Text")
    private val a = Node(id = NodeId("a"), type = "compose.material3.Text")
    private val b = Node(
        id = NodeId("b"),
        type = "compose.material3.Button",
        slots = mapOf("content" to listOf(leaf)),
    )
    private val root = Node(
        id = NodeId("root"),
        type = "compose.foundation.layout.Column",
        children = listOf(a, b),
    )

    private val newNode = Node(id = NodeId("new"), type = "compose.material3.Text")

    @Test
    fun `locate finds a default-region child`() {
        assertEquals(ChildAddress(NodeId("root"), null, 0), root.locate(NodeId("a")))
        assertEquals(ChildAddress(NodeId("root"), null, 1), root.locate(NodeId("b")))
    }

    @Test
    fun `locate finds a slotted child under its parent and slot`() {
        assertEquals(ChildAddress(NodeId("b"), "content", 0), root.locate(NodeId("leaf")))
    }

    @Test
    fun `locate returns null for the root and for absent ids`() {
        assertNull(root.locate(NodeId("root")))
        assertNull(root.locate(NodeId("nope")))
    }

    @Test
    fun `subtreeContains covers self, descendants, and slotted descendants`() {
        assertTrue(root.subtreeContains(NodeId("root")))
        assertTrue(root.subtreeContains(NodeId("leaf")))
        assertTrue(b.subtreeContains(NodeId("leaf")))
        assertFalse(b.subtreeContains(NodeId("a")))
    }

    @Test
    fun `insertChild places into the default region at the index`() {
        val out = root.insertChild(ChildAddress(NodeId("root"), null, 1), newNode)
        assertEquals(listOf(NodeId("a"), NodeId("new"), NodeId("b")), out.children.map { it.id })
    }

    @Test
    fun `insertChild clamps an out-of-range index`() {
        val out = root.insertChild(ChildAddress(NodeId("root"), null, 99), newNode)
        assertEquals(listOf(NodeId("a"), NodeId("b"), NodeId("new")), out.children.map { it.id })
    }

    @Test
    fun `insertChild places into a named slot`() {
        val out = root.insertChild(ChildAddress(NodeId("b"), "content", 0), newNode)
        val slot = out.findById(NodeId("b"))!!.slots.getValue("content")
        assertEquals(listOf(NodeId("new"), NodeId("leaf")), slot.map { it.id })
    }

    @Test
    fun `removeChild removes a default child and a slotted child`() {
        assertEquals(listOf(NodeId("b")), root.removeChild(NodeId("a")).children.map { it.id })
        val afterSlot = root.removeChild(NodeId("leaf"))
        assertTrue(afterSlot.findById(NodeId("b"))!!.slots.getValue("content").isEmpty())
    }

    @Test
    fun `replaceNode swaps a node in place`() {
        val renamed = a.copy(name = "Renamed")
        val out = root.replaceNode(NodeId("a"), renamed)
        assertEquals("Renamed", out.children[0].name)
        assertEquals(NodeId("a"), out.children[0].id)
    }

    @Test
    fun `withFreshIds reassigns every node and modifier id`() {
        val src = Node(
            id = NodeId("x"),
            type = "compose.material3.Button",
            modifiers = listOf(ModifierEntry(id = "m1", type = "compose.padding")),
            slots = mapOf("content" to listOf(Node(id = NodeId("y"), type = "compose.material3.Text"))),
        )
        val clone = src.withFreshIds()
        assertNotSame(src.id, clone.id)
        assertTrue(src.id != clone.id)
        assertTrue(src.modifiers[0].id != clone.modifiers[0].id)
        val srcChild = src.slots.getValue("content")[0]
        val cloneChild = clone.slots.getValue("content")[0]
        assertTrue(srcChild.id != cloneChild.id)
        // Shape and non-id content are preserved.
        assertEquals(src.type, clone.type)
        assertEquals(src.modifiers[0].type, clone.modifiers[0].type)
    }

    // --- structural sharing -----------------------------------------------------------------------

    @Test
    fun `edits share untouched siblings by identity`() {
        // Insert into b's slot: sibling a and its subtree must be the SAME instances.
        val out = root.insertChild(ChildAddress(NodeId("b"), "content", 1), newNode)
        assertNotSame(root, out) // root path rebuilt
        assertSame(a, out.children[0]) // untouched sibling shared
    }

    @Test
    fun `an edit that matches nothing returns the same instance`() {
        assertSame(root, root.removeChild(NodeId("absent")))
        assertSame(root, root.replaceNode(NodeId("absent"), newNode))
    }

    @Test
    fun `updateScreenRoot shares other screens and no-ops when unchanged`() {
        val screenA = Screen(id = "s1", name = "A", root = root)
        val screenB = Screen(id = "s2", name = "B", root = a)
        val project = Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(screenA, screenB),
        )

        val edited = project.updateScreenRoot("s1") { it.removeChild(NodeId("a")) }
        assertNotSame(project, edited)
        assertSame(screenB, edited.screens[1]) // untouched screen shared

        val unchanged = project.updateScreenRoot("s1") { it } // returns same root
        assertSame(project, unchanged)
    }
}

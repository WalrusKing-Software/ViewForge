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
    fun `withProp sets, overwrites, and removes a prop`() {
        val text = Node(id = NodeId("t"), type = "compose.material3.Text")
        val v = PropValue.Literal(kotlinx.serialization.json.JsonPrimitive("Hi"))
        val set = text.withProp("text", v)
        assertEquals(v, set.props["text"])
        // Removing (null) drops the key; setting the identical value is a no-op (same instance).
        assertSame(set, set.withProp("text", v))
        assertTrue("text" !in set.withProp("text", null).props)
        assertSame(text, text.withProp("text", null)) // removing an absent key changes nothing
    }

    @Test
    fun `withModifiers replaces the ordered chain and no-ops when equal`() {
        val m1 = ModifierEntry(id = "m1", type = "compose.padding")
        val node = Node(id = NodeId("n"), type = "compose.foundation.layout.Box", modifiers = listOf(m1))
        val m2 = ModifierEntry(id = "m2", type = "compose.fillMaxSize")
        assertEquals(listOf(m1, m2), node.withModifiers(listOf(m1, m2)).modifiers)
        assertSame(node, node.withModifiers(listOf(m1)))
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

    // --- root-agnostic editing (edit-in-place slice 1, ADR-027) -----------------------------------

    private val component = ComponentDef(id = "c1", name = "PrimaryButton", root = b)

    private fun projectWith(screen: Screen, comp: ComponentDef) = Project(
        id = "p",
        name = "P",
        framework = FrameworkRef("compose-multiplatform", "1.0.0"),
        screens = listOf(screen),
        components = listOf(comp),
    )

    @Test
    fun `updateComponentRoot transforms a component and shares others and no-ops when unchanged`() {
        val other = ComponentDef(id = "c2", name = "Other", root = a)
        val project = projectWith(Screen("s1", "A", root), component).copy(components = listOf(component, other))

        val edited = project.updateComponentRoot("c1") { it.removeChild(NodeId("leaf")) }
        assertNotSame(project, edited)
        assertSame(other, edited.components[1]) // untouched component shared
        assertSame(project.screens[0], edited.screens[0]) // screens untouched

        assertSame(project, project.updateComponentRoot("c1") { it }) // same root ⇒ same project
    }

    @Test
    fun `updateRoot dispatches to a screen or a component by id and no-ops on an unknown id`() {
        val project = projectWith(Screen("s1", "A", root), component)

        val screenEdited = project.updateRoot("s1") { it.removeChild(NodeId("a")) }
        assertNull(screenEdited.screens[0].root.findById(NodeId("a")))
        assertSame(project.components[0], screenEdited.components[0]) // component untouched

        val compEdited = project.updateRoot("c1") { it.removeChild(NodeId("leaf")) }
        assertNull(compEdited.components[0].root.findById(NodeId("leaf")))
        assertSame(project.screens[0], compEdited.screens[0]) // screen untouched

        assertSame(project, project.updateRoot("nope") { it.removeChild(NodeId("a")) })
    }

    @Test
    fun `findRoot resolves a screen root, a component root, or null`() {
        val project = projectWith(Screen("s1", "A", root), component)
        assertSame(root, project.findRoot("s1"))
        assertSame(b, project.findRoot("c1"))
        assertNull(project.findRoot("nope"))
    }
}

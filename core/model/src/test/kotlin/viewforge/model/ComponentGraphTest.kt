package viewforge.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The user-component reference-graph queries (#70): collecting referenced ids and deciding whether an
 * insert would close a cycle. Pure and framework-free, so the load-time validator and the edit-time
 * guard can share one authoritative answer.
 */
class ComponentGraphTest {
    private fun box(vararg children: Node) = Node(id = NodeId.random(), type = "box", children = children.toList())

    /** A component whose tree contains an instance of each id in [refs]. */
    private fun component(id: String, vararg refs: String) =
        ComponentDef(id = id, name = id, root = box(*refs.map { UserComponent.instance(it) }.toTypedArray()))

    private fun project(vararg components: ComponentDef) = Project(
        id = "p",
        name = "P",
        framework = FrameworkRef("compose-multiplatform", "1.0.0"),
        components = components.toList(),
    )

    @Test
    fun `referencedComponentIds collects instances across children and slots`() {
        val node = Node(
            id = NodeId.random(),
            type = "box",
            children = listOf(UserComponent.instance("a"), box(UserComponent.instance("b"))),
            slots = mapOf("content" to listOf(UserComponent.instance("c"))),
        )
        assertEquals(setOf("a", "b", "c"), node.referencedComponentIds())
    }

    @Test
    fun `referencedComponentIds is empty for a tree with no instances`() {
        assertTrue(box(Node(NodeId.random(), "compose.material3.Text")).referencedComponentIds().isEmpty())
    }

    @Test
    fun `editing a screen (null target) never cycles`() {
        val p = project(component("a"))
        assertFalse(p.insertionWouldCycle(null, UserComponent.instance("a")))
    }

    @Test
    fun `inserting an instance of the edited component into itself cycles`() {
        val p = project(component("a"))
        assertTrue(p.insertionWouldCycle("a", UserComponent.instance("a")))
    }

    @Test
    fun `inserting an independent component does not cycle`() {
        val p = project(component("a"), component("b"))
        assertFalse(p.insertionWouldCycle("a", UserComponent.instance("b")))
    }

    @Test
    fun `inserting a component that transitively contains the edited one cycles`() {
        // c -> b -> a already; editing a and inserting c would make a -> c -> b -> a.
        val p = project(component("a"), component("b", "a"), component("c", "b"))
        assertTrue(p.insertionWouldCycle("a", UserComponent.instance("c")))
        // The reverse insert (editing c, adding a) is legal — a references nothing.
        assertFalse(p.insertionWouldCycle("c", UserComponent.instance("a")))
    }

    @Test
    fun `a built-in insert never cycles`() {
        val p = project(component("a"))
        assertFalse(p.insertionWouldCycle("a", Node(NodeId.random(), "compose.material3.Text")))
    }

    @Test
    fun `a pasted subtree that contains an instance of the edited component cycles`() {
        val p = project(component("a"), component("b"))
        val pasted = box(Node(NodeId.random(), "box", children = listOf(UserComponent.instance("a"))))
        assertTrue(p.insertionWouldCycle("a", pasted))
    }

    @Test
    fun `an instance of an unknown component id does not cycle`() {
        val p = project(component("a"))
        assertFalse(p.insertionWouldCycle("a", UserComponent.instance("ghost")))
    }

    // --- transitive closure + reference remap (#234) ----------------------------------------------

    @Test
    fun `reachableComponents is empty for a self-contained component`() {
        val p = project(component("a"))
        assertEquals(emptyList(), p.reachableComponents("a"))
    }

    @Test
    fun `reachableComponents collects a transitive chain, excluding the primary itself`() {
        // c -> b -> a. From c the closure is {b, a}; the primary c is never included.
        val p = project(component("a"), component("b", "a"), component("c", "b"))
        assertEquals(listOf("a", "b"), p.reachableComponents("c")?.map { it.id }?.sorted())
    }

    @Test
    fun `reachableComponents dedupes a diamond`() {
        // top -> {left, right}; both -> leaf. leaf appears once.
        val p = project(
            component("leaf"),
            component("left", "leaf"),
            component("right", "leaf"),
            component("top", "left", "right"),
        )
        assertEquals(listOf("leaf", "left", "right"), p.reachableComponents("top")?.map { it.id }?.sorted())
    }

    @Test
    fun `reachableComponents is null when a reference dangles`() {
        val p = project(component("a", "ghost")) // 'ghost' is not a component in the project
        assertNull(p.reachableComponents("a"))
    }

    @Test
    fun `reachableComponents is null for an unknown primary`() {
        assertNull(project(component("a")).reachableComponents("missing"))
    }

    @Test
    fun `remapComponentReferences rewrites mapped ids and leaves the rest alone`() {
        val tree = Node(
            id = NodeId.random(),
            type = "box",
            children = listOf(UserComponent.instance("a"), box(UserComponent.instance("b"))),
            slots = mapOf("content" to listOf(UserComponent.instance("c"))),
        )
        val remapped = tree.remapComponentReferences(mapOf("a" to "a2", "b" to "b2"))
        assertEquals(setOf("a2", "b2", "c"), remapped.referencedComponentIds()) // 'c' unmapped → untouched
    }

    @Test
    fun `remapComponentReferences is an identity when nothing matches`() {
        val tree = box(UserComponent.instance("x"))
        assertEquals(setOf("x"), tree.remapComponentReferences(mapOf("y" to "z")).referencedComponentIds())
    }
}

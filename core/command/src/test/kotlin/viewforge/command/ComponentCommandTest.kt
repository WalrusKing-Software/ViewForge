package viewforge.command

import kotlinx.serialization.json.JsonPrimitive
import viewforge.command.Fixtures.rootOf
import viewforge.model.ComponentDef
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropValue
import viewforge.model.findById
import viewforge.model.withFreshIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reusable-component commands (D7). Each must apply cleanly and invert back to the exact prior
 * state so undo/redo round-trips; extract is the composite that turns a selection into a definition
 * plus an instance in one undoable step. Guards (absent target) collapse to a no-op inverse rather
 * than corrupting history.
 */
class ComponentCommandTest {
    private fun component(id: String, name: String = id) =
        ComponentDef(id = id, name = name, root = Node(NodeId("$id-root"), "compose.foundation.layout.Column"))

    private fun instanceOf(componentId: String, id: String = "inst") = Node(
        id = NodeId(id),
        type = "vforge.userComponent",
        props = mapOf("componentId" to PropValue.Literal(JsonPrimitive(componentId))),
    )

    @Test
    fun `AddComponent inserts at the index and inverts by removing it`() {
        val before = Fixtures.project().copy(components = listOf(component("c1"), component("c2")))
        val cmd = AddComponent(component("c3"), index = 1)
        val after = cmd.apply(before)
        assertEquals(listOf("c1", "c3", "c2"), after.components.map { it.id })

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.components, restored.components)
    }

    @Test
    fun `AddComponent clamps an out-of-range index to an append`() {
        val before = Fixtures.project().copy(components = listOf(component("c1")))
        val after = AddComponent(component("c2"), index = Int.MAX_VALUE).apply(before)
        assertEquals(listOf("c1", "c2"), after.components.map { it.id })
    }

    @Test
    fun `RemoveComponent removes it and inverts by restoring it at its old index`() {
        val before = Fixtures.project().copy(components = listOf(component("c1"), component("c2")))
        val cmd = RemoveComponent("c1") // remove the first so restoring at index 0 is meaningful
        val after = cmd.apply(before)
        assertEquals(listOf("c2"), after.components.map { it.id })

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.components, restored.components)
    }

    @Test
    fun `RemoveComponent of an absent id is a no-op with a no-op inverse`() {
        val before = Fixtures.project().copy(components = listOf(component("c1")))
        assertEquals(before.components, RemoveComponent("nope").apply(before).components)
        assertTrue(RemoveComponent("nope").invert(before) is NoOp)
    }

    @Test
    fun `RenameComponent changes the name and inverts to the old one`() {
        val before = Fixtures.project().copy(components = listOf(component("c1", "Old")))
        val cmd = RenameComponent("c1", "PrimaryButton")
        val after = cmd.apply(before)
        assertEquals("PrimaryButton", after.components.first().name)

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.components, restored.components)
    }

    @Test
    fun `RenameComponent of an absent id inverts to a no-op`() {
        val before = Fixtures.project().copy(components = listOf(component("c1")))
        assertTrue(RenameComponent("nope", "X").invert(before) is NoOp)
    }

    @Test
    fun `ReplaceNode swaps a node in place and inverts by swapping it back`() {
        val before = Fixtures.project()
        val instance = instanceOf("cmp1")
        val cmd = ReplaceNode(Fixtures.SCREEN, Fixtures.button.id, instance)
        val after = cmd.apply(before)

        // The button is gone; the instance sits where it was (same parent, same index).
        assertNull(after.rootOf().findById(Fixtures.button.id))
        assertEquals(instance, after.rootOf().findById(instance.id))
        assertEquals(listOf(Fixtures.text.id, instance.id), after.rootOf().children.map { it.id })

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.rootOf(), restored.rootOf())
    }

    @Test
    fun `ReplaceNode of an absent id is a no-op with a no-op inverse`() {
        val before = Fixtures.project()
        val cmd = ReplaceNode(Fixtures.SCREEN, NodeId("nope"), instanceOf("cmp1"))
        assertEquals(before.rootOf(), cmd.apply(before).rootOf())
        assertTrue(cmd.invert(before) is NoOp)
    }

    @Test
    fun `extractComponent lifts the subtree into a component and leaves an instance, undoing exactly`() {
        val before = Fixtures.project()
        // Extract the button subtree (which has a content slot) into a new component "cmp1".
        val def = ComponentDef(id = "cmp1", name = "PrimaryButton", root = Fixtures.button)
        val instance = instanceOf("cmp1")
        val cmd = extractComponent(Fixtures.SCREEN, Fixtures.button.id, def, instance)
        val after = cmd.apply(before)

        // The definition holds the original subtree verbatim (ids preserved).
        assertEquals(listOf(def), after.components)
        assertEquals(Fixtures.button, after.components.single().root)
        // The screen now holds the instance in the button's place, referencing the component id.
        assertNull(after.rootOf().findById(Fixtures.button.id))
        val placed = after.rootOf().findById(instance.id)
        assertEquals(instance, placed)
        assertEquals(
            PropValue.Literal(JsonPrimitive("cmp1")),
            placed?.props?.get("componentId"),
        )

        // One undo removes the definition and restores the exact original screen.
        val restored = cmd.invert(before).apply(after)
        assertEquals(before.components, restored.components)
        assertEquals(before.rootOf(), restored.rootOf())
    }

    @Test
    fun `promoteScreenToComponent publishes a fresh-id copy and leaves the screen intact, undoing exactly`() {
        val before = Fixtures.project()
        val screenRoot = before.rootOf()
        // Built exactly as EditorState.saveScreenAsComponent does: a fresh-id copy of the screen's root,
        // so the screen is untouched (copy, not move) and the two roots never share node ids.
        val def = ComponentDef(id = "cmp1", name = "HomeCard", root = screenRoot.withFreshIds())
        val cmd = promoteScreenToComponent(def)
        val after = cmd.apply(before)

        // The component is published, appended to the list; the source screen is byte-identical.
        assertEquals(listOf("cmp1"), after.components.map { it.id })
        assertEquals(before.screens, after.screens)

        // The correctness crux: the copy's node ids are disjoint from the screen's (no duplicate ULIDs)…
        assertTrue(collectIds(screenRoot).intersect(collectIds(after.components.single().root)).isEmpty())
        // …while the tree structure is preserved.
        assertEquals(types(screenRoot), types(after.components.single().root))

        // One undo removes the definition; the screen was never modified.
        val restored = cmd.invert(before).apply(after)
        assertEquals(before.components, restored.components)
        assertEquals(before.screens, restored.screens)
    }

    private fun collectIds(node: Node): Set<NodeId> = buildSet {
        add(node.id)
        node.children.forEach { addAll(collectIds(it)) }
        node.slots.values.forEach { list -> list.forEach { addAll(collectIds(it)) } }
    }

    private fun types(node: Node): List<String> = buildList {
        add(node.type)
        node.children.forEach { addAll(types(it)) }
        node.slots.values.forEach { list -> list.forEach { addAll(types(it)) } }
    }
}

package viewforge.command

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ComponentDef
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Parameter
import viewforge.model.PropValue
import viewforge.model.findById
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The component-parameter commands (parameters slice 4a, ADR-028). Each must apply cleanly and invert
 * back to the exact prior state so undo/redo round-trips; promote is the composite that adds a parameter
 * and rebinds a prop to a ParamRef in one undoable step. Guards (absent target, duplicate name) collapse
 * to a no-op inverse rather than corrupting history.
 */
class ParameterCommandTest {
    private val label = Parameter("label", "String", PropValue.Literal(JsonPrimitive("Click")))
    private val enabled = Parameter("enabled", "Boolean", PropValue.Literal(JsonPrimitive(true)))

    private fun component(vararg params: Parameter) = ComponentDef(
        id = "c1",
        name = "PrimaryButton",
        parameters = params.toList(),
        root = Node(
            NodeId("c1-root"),
            "compose.material3.Text",
            props = mapOf(
                "text" to PropValue.Literal(JsonPrimitive("Hi")),
            ),
        ),
    )

    private fun projectWith(component: ComponentDef) = Fixtures.project().copy(components = listOf(component))

    @Test
    fun `AddParameter appends and inverts by removing it`() {
        val before = projectWith(component())
        val cmd = AddParameter("c1", label, index = Int.MAX_VALUE)
        val after = cmd.apply(before)
        assertEquals(listOf(label), after.components.single().parameters)

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.components, restored.components)
    }

    @Test
    fun `AddParameter with a duplicate name is a no-op with a no-op inverse`() {
        val before = projectWith(component(label))
        val cmd = AddParameter("c1", label.copy(default = null), index = 0)
        assertEquals(before.components, cmd.apply(before).components)
        assertTrue(cmd.invert(before) is NoOp)
    }

    @Test
    fun `AddParameter to an absent component is a no-op`() {
        val before = projectWith(component())
        assertEquals(before.components, AddParameter("nope", label, 0).apply(before).components)
    }

    @Test
    fun `RemoveParameter removes it and inverts by restoring it at its old index`() {
        val before = projectWith(component(label, enabled))
        val cmd = RemoveParameter("c1", "label") // remove the first so restoring at index 0 is meaningful
        val after = cmd.apply(before)
        assertEquals(listOf(enabled), after.components.single().parameters)

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.components, restored.components)
    }

    @Test
    fun `RemoveParameter of an absent name is a no-op with a no-op inverse`() {
        val before = projectWith(component(label))
        assertEquals(before.components, RemoveParameter("c1", "nope").apply(before).components)
        assertTrue(RemoveParameter("c1", "nope").invert(before) is NoOp)
    }

    @Test
    fun `promoteToParameter adds the parameter and binds the prop to a ParamRef, undoing both`() {
        val before = projectWith(component())
        val nodeId = NodeId("c1-root")
        val param = Parameter("text", "String", PropValue.Literal(JsonPrimitive("Hi")))
        val cmd = promoteToParameter("c1", nodeId, "text", param)
        val after = cmd.apply(before)

        assertEquals(listOf(param), after.components.single().parameters)
        assertEquals(PropValue.ParamRef("text"), after.components.single().root.findById(nodeId)?.props?.get("text"))

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.components, restored.components)
    }
}

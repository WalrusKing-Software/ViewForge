package viewforge.packages.compose.render

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ComponentDef
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropValue
import viewforge.model.UserComponent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure half of instance rendering (D7, ADR-024): resolving a `vforge.userComponent` node to the
 * definition it references, or reporting why it can't be drawn. The [RenderUserComponent] composable is
 * a thin renderer of these outcomes; keeping the decision pure makes the missing/cycle/resolved cases
 * testable without a composition.
 */
class UserComponentResolutionTest {
    private fun def(id: String, name: String = id) =
        ComponentDef(id = id, name = name, root = Node(NodeId("$id-root"), "compose.foundation.layout.Column"))

    private fun instance(componentId: String?) = Node(
        id = NodeId("inst"),
        type = UserComponent.TYPE,
        props = if (componentId == null) {
            emptyMap()
        } else {
            mapOf(UserComponent.COMPONENT_ID_PROP to PropValue.Literal(JsonPrimitive(componentId)))
        },
    )

    @Test
    fun `resolves an instance to its component definition`() {
        val primary = def("c1", "PrimaryButton")
        val result = resolveUserComponent(instance("c1"), mapOf("c1" to primary), expanding = emptySet())
        assertEquals(InstanceResolution.Resolved(primary), result)
    }

    @Test
    fun `reports Missing when no component matches the id`() {
        val result = resolveUserComponent(instance("nope"), mapOf("c1" to def("c1")), expanding = emptySet())
        assertEquals(InstanceResolution.Missing("nope"), result)
    }

    @Test
    fun `reports Missing with a null id when the componentId prop is absent`() {
        val result = resolveUserComponent(instance(null), mapOf("c1" to def("c1")), expanding = emptySet())
        assertEquals(InstanceResolution.Missing(null), result)
    }

    @Test
    fun `reports Cycle when the referenced component is already being rendered above`() {
        val primary = def("c1", "PrimaryButton")
        val result = resolveUserComponent(instance("c1"), mapOf("c1" to primary), expanding = setOf("c1"))
        assertEquals(InstanceResolution.Cycle(primary), result)
    }
}

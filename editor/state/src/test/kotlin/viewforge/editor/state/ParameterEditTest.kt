package viewforge.editor.state

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ComponentDef
import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Parameter
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.PropType
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.model.UserComponent
import viewforge.model.findById
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Promote-in-place (parameters slice 4a, ADR-028): while a component is open for editing, a prop can be
 * promoted to a component parameter — adding the parameter (defaulting to the prop's value) and rebinding
 * the prop to a ParamRef in one undoable step. Only value-typed props, only while a component is open,
 * only when the prop is not already bound.
 */
class ParameterEditTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = emptyList<PaletteEntry>()

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = type.startsWith("compose.foundation.layout.")

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = when (type) {
            "compose.material3.Text" -> listOf(
                PropDefinition("text", PropType.String, default = PropValue.Literal(JsonPrimitive(""))),
                PropDefinition("textAlign", PropType.Enum, enumValues = listOf("Start", "Center")),
            )
            else -> emptyList()
        }

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private fun textNode(id: String, text: String? = null) = Node(
        id = NodeId(id),
        type = "compose.material3.Text",
        props = if (text == null) emptyMap() else mapOf("text" to PropValue.Literal(JsonPrimitive(text))),
    )

    private fun state(compRoot: Node): EditorState = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(Screen("s1", "Home", Node(NodeId("s-root"), "compose.foundation.layout.Column"))),
            components = listOf(ComponentDef(id = "c1", name = "PrimaryButton", root = compRoot)),
        ),
        FakeCatalog(),
    )

    private fun boxWith(vararg children: Node) =
        Node(NodeId("c-root"), "compose.foundation.layout.Box", children = children.toList())

    private fun component(s: EditorState) = s.document.components.first { it.id == "c1" }

    @Test
    fun `promoting a prop adds a parameter defaulting to its value and binds the prop`() {
        val s = state(boxWith(textNode("t1", "Hi")))
        s.openComponent("c1")
        s.promotePropToParameter(NodeId("t1"), "text")

        assertEquals(
            listOf(Parameter("text", "String", PropValue.Literal(JsonPrimitive("Hi")))),
            component(s).parameters,
        )
        assertEquals(PropValue.ParamRef("text"), component(s).root.findById(NodeId("t1"))?.props?.get("text"))
    }

    @Test
    fun `promote is a no-op when no component is open`() {
        val s = state(boxWith(textNode("t1", "Hi")))
        s.promotePropToParameter(NodeId("t1"), "text") // nothing opened
        assertTrue(component(s).parameters.isEmpty())
    }

    @Test
    fun `canPromoteToParameter gates on surface, value type, and existing binding`() {
        val s = state(boxWith(textNode("t1", "Hi")))
        val defs = FakeCatalog().propsFor("compose.material3.Text")
        val stringDef = defs.first { it.name == "text" }
        val enumDef = defs.first { it.name == "textAlign" }
        val node = textNode("t1", "Hi")

        assertFalse(s.canPromoteToParameter(node, stringDef)) // no component open
        s.openComponent("c1")
        assertTrue(s.canPromoteToParameter(node, stringDef)) // open + value type
        assertFalse(s.canPromoteToParameter(node, enumDef)) // Enum is not representable
        val bound = node.copy(props = mapOf("text" to PropValue.ParamRef("text")))
        assertFalse(s.canPromoteToParameter(bound, stringDef)) // already bound
    }

    @Test
    fun `undo reverses a promote`() {
        val s = state(boxWith(textNode("t1", "Hi")))
        s.openComponent("c1")
        s.promotePropToParameter(NodeId("t1"), "text")
        s.undo()

        assertTrue(component(s).parameters.isEmpty())
        assertEquals(
            PropValue.Literal(JsonPrimitive("Hi")),
            component(s).root.findById(NodeId("t1"))?.props?.get("text"),
        )
    }

    @Test
    fun `a second promote of the same prop name gets a unique parameter name`() {
        val s = state(boxWith(textNode("t1", "A"), textNode("t2", "B")))
        s.openComponent("c1")
        s.promotePropToParameter(NodeId("t1"), "text")
        s.promotePropToParameter(NodeId("t2"), "text")

        assertEquals(listOf("text", "text2"), component(s).parameters.map { it.name })
        assertEquals(PropValue.ParamRef("text2"), component(s).root.findById(NodeId("t2"))?.props?.get("text"))
    }

    // --- slice 4b: instance argument editing ---------------------------------------------------

    @Test
    fun `componentOfInstance resolves an instance to its definition`() {
        val s = state(boxWith(textNode("t1")))
        assertEquals("c1", s.componentOfInstance(UserComponent.instance("c1", NodeId("i1")))?.id)
    }

    @Test
    fun `componentOfInstance is null for a non-instance node or an unknown id`() {
        val s = state(boxWith(textNode("t1")))
        assertNull(s.componentOfInstance(textNode("t1")))
        assertNull(s.componentOfInstance(UserComponent.instance("nope", NodeId("i1"))))
    }

    @Test
    fun `editing an instance argument sets the prop and undoes`() {
        // An instance of c1 sits on the screen; setting its 'label' argument is the inspector's edit path.
        val instance = UserComponent.instance("c1", NodeId("i1"))
        val screenRoot = Node(NodeId("s-root"), "compose.foundation.layout.Column", children = listOf(instance))
        val s = EditorState(
            Project(
                id = "p",
                name = "P",
                framework = FrameworkRef("compose-multiplatform", "1.0.0"),
                screens = listOf(Screen("s1", "Home", screenRoot)),
                components = listOf(ComponentDef(id = "c1", name = "PrimaryButton", root = boxWith(textNode("t1")))),
            ),
            FakeCatalog(),
        )
        s.select(NodeId("i1"))
        s.setProp(NodeId("i1"), "label", PropValue.Literal(JsonPrimitive("Hi")))

        fun arg() = s.document.screens.first().root.findById(NodeId("i1"))?.props?.get("label")
        assertEquals(PropValue.Literal(JsonPrimitive("Hi")), arg())
        s.undo()
        assertNull(arg())
    }
}

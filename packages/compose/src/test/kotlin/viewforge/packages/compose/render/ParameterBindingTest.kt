package viewforge.packages.compose.render

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ComponentDef
import viewforge.model.ModifierEntry
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Parameter
import viewforge.model.PropValue
import viewforge.model.UserComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * The pure half of parameter rendering (parameters slice 3, ADR-028): substituting a resolved
 * instance's argument values into the definition tree so a ParamRef draws the instance's value (or the
 * parameter default). Testable without a composition.
 */
class ParameterBindingTest {
    private fun lit(v: Any) = PropValue.Literal(
        when (v) {
            is Boolean -> JsonPrimitive(v)
            is Int -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        },
    )

    private fun param(name: String, type: String, default: PropValue? = null) = Parameter(name, type, default)

    /** PrimaryButton(label: String, enabled: Boolean = true) → Button(enabled=param) { Text(text=param) }. */
    private fun primaryButton() = ComponentDef(
        id = "cmp",
        name = "PrimaryButton",
        parameters = listOf(param("label", "String"), param("enabled", "Boolean", lit(true))),
        root = Node(
            id = NodeId("btn"),
            type = "compose.material3.Button",
            props = mapOf("enabled" to PropValue.ParamRef("enabled")),
            slots = mapOf(
                "content" to listOf(
                    Node(NodeId("txt"), "compose.material3.Text", props = mapOf("text" to PropValue.ParamRef("label"))),
                ),
            ),
        ),
    )

    private fun instance(args: Map<String, PropValue>) = Node(
        id = NodeId("inst"),
        type = UserComponent.TYPE,
        props = mapOf(UserComponent.COMPONENT_ID_PROP to lit("cmp")) + args,
    )

    @Test
    fun `an instance's arguments replace the ParamRefs in the definition tree`() {
        val bound = bindParameters(primaryButton(), instance(mapOf("label" to lit("Hi"), "enabled" to lit(false))))
        assertEquals(lit(false), bound.props["enabled"])
        assertEquals(lit("Hi"), bound.slots.getValue("content").single().props["text"])
    }

    @Test
    fun `an omitted argument falls back to the parameter default`() {
        val bound = bindParameters(primaryButton(), instance(mapOf("label" to lit("Hi"))))
        assertEquals(lit(true), bound.props["enabled"]) // default true
    }

    @Test
    fun `a parameter with no argument and no default drops the prop so the render default applies`() {
        val def = ComponentDef(
            id = "c",
            name = "Labelled",
            parameters = listOf(param("label", "String")), // no default
            root = Node(NodeId("t"), "compose.material3.Text", props = mapOf("text" to PropValue.ParamRef("label"))),
        )
        val bound = bindParameters(def, instance(emptyMap()))
        assertFalse("text" in bound.props, "unbound ParamRef prop should be dropped, got ${bound.props}")
    }

    @Test
    fun `a definition with no parameters returns its root unchanged`() {
        val def = ComponentDef(id = "c", name = "Plain", root = Node(NodeId("r"), "compose.foundation.layout.Column"))
        assertSame(def.root, bindParameters(def, instance(emptyMap())))
    }

    @Test
    fun `a ParamRef inside a modifier argument is substituted`() {
        val def = ComponentDef(
            id = "c",
            name = "Padded",
            parameters = listOf(param("gap", "Dp", lit(4))),
            root = Node(
                id = NodeId("r"),
                type = "compose.foundation.layout.Column",
                modifiers = listOf(
                    ModifierEntry("m", "compose.padding", args = mapOf("all" to PropValue.ParamRef("gap"))),
                ),
            ),
        )
        val bound = bindParameters(def, instance(mapOf("gap" to lit(16))))
        assertEquals(lit(16), bound.modifiers.single().args["all"])
    }

    @Test
    fun `a ParamRef in a nested instance's argument resolves in the enclosing definition's scope`() {
        val def = ComponentDef(
            id = "outer",
            name = "Outer",
            parameters = listOf(param("text", "String")),
            root = Node(
                id = NodeId("col"),
                type = "compose.foundation.layout.Column",
                children = listOf(
                    Node(
                        id = NodeId("nested"),
                        type = UserComponent.TYPE,
                        props = mapOf(
                            UserComponent.COMPONENT_ID_PROP to lit("inner"),
                            "label" to PropValue.ParamRef("text"),
                        ),
                    ),
                ),
            ),
        )
        val bound = bindParameters(def, instance(mapOf("text" to lit("Hello"))))
        val nested = bound.children.single()
        assertEquals(lit("Hello"), nested.props["label"])
        assertEquals(lit("inner"), nested.props[UserComponent.COMPONENT_ID_PROP]) // untouched
    }
}

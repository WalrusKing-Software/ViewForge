package viewforge.editor.panels

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ModifierEntry
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropValue
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeDisplayTest {
    @Test
    fun `short type name is the last segment`() {
        assertEquals("Button", shortTypeName("compose.material3.Button"))
        assertEquals("Column", shortTypeName("compose.foundation.layout.Column"))
    }

    @Test
    fun `display label prefers the user name then falls back to the short type`() {
        val named = Node(id = NodeId("1"), type = "compose.material3.Text", name = "Title")
        val unnamed = Node(id = NodeId("2"), type = "compose.material3.Text")
        assertEquals("Title", displayLabel(named))
        assertEquals("Text", displayLabel(unnamed))
    }

    @Test
    fun `display label resolves a component instance to its component name`() {
        val instance = Node(id = NodeId("3"), type = "vforge.userComponent")
        // A resolved component name is used when there is no user-given name (#305).
        assertEquals("ProfileCard", displayLabel(instance, componentName = "ProfileCard"))
        // An unresolvable / dangling id falls back to the short type.
        assertEquals("userComponent", displayLabel(instance, componentName = null))
        // A user-given name still wins over the resolved component name.
        val renamed = Node(id = NodeId("4"), type = "vforge.userComponent", name = "My Card")
        assertEquals("My Card", displayLabel(renamed, componentName = "ProfileCard"))
    }

    @Test
    fun `literal prop values show their content`() {
        assertEquals("Welcome", formatPropValue(PropValue.Literal(JsonPrimitive("Welcome"))))
        assertEquals("24", formatPropValue(PropValue.Literal(JsonPrimitive(24))))
    }

    @Test
    fun `reference prop values are labelled by kind`() {
        assertEquals("→ theme: colors.primary", formatPropValue(PropValue.ThemeRef("colors.primary")))
        assertEquals("→ resource: logo", formatPropValue(PropValue.ResourceRef("logo")))
        assertEquals("→ binding: user.name", formatPropValue(PropValue.StateBinding("user.name")))
    }

    @Test
    fun `raw expressions are shown verbatim and flagged unverified`() {
        assertEquals("{ nav() }  (unverified)", formatPropValue(PropValue.RawExpression("{ nav() }")))
    }

    @Test
    fun `modifier formatting keeps args and marks disabled entries`() {
        val padding =
            ModifierEntry(
                id = "m1",
                type = "compose.padding",
                args = mapOf("all" to PropValue.Literal(JsonPrimitive(16))),
            )
        assertEquals("padding(all=16)", formatModifier(padding))
        assertEquals("fillMaxSize", formatModifier(ModifierEntry(id = "m2", type = "compose.fillMaxSize")))
        assertEquals(
            "fillMaxSize — disabled",
            formatModifier(ModifierEntry(id = "m3", type = "compose.fillMaxSize", enabled = false)),
        )
    }
}

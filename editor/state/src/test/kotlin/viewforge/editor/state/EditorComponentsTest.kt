package viewforge.editor.state

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.model.UserComponent
import viewforge.model.findById
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reusable-component behaviour of [EditorState] (D7/P6a): extracting a selection into a component,
 * undoing it, surfacing user components in the palette, and inserting an instance from the palette.
 * A [FakeCatalog] stands in for the Compose package so these run without a composition.
 */
class EditorComponentsTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(
            PaletteEntry("compose.foundation.layout.Column", "Column", "Layout"),
            PaletteEntry("compose.material3.Text", "Text", "Content"),
        )

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = type.startsWith("compose.foundation.layout.")

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private val button = Node(NodeId("b"), "compose.material3.Button")
    private val root = Node(
        id = NodeId("root"),
        type = "compose.foundation.layout.Column",
        children = listOf(Node(NodeId("a"), "compose.material3.Text"), button),
    )

    private fun state(): EditorState = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(Screen("s1", "Home", root)),
        ),
        FakeCatalog(),
    )

    @Test
    fun `extract turns the selection into a component and leaves a selected instance in its place`() {
        val s = state()
        s.select(NodeId("b"))
        s.extractSelectionToComponent("PrimaryButton")

        // A new component holds the original subtree verbatim.
        val component = s.document.components.single()
        assertEquals("PrimaryButton", component.name)
        assertEquals(button, component.root)

        // The screen no longer holds the button; an instance referencing the component sits there, selected.
        val screenRoot = s.activeScreen!!.root
        assertNull(screenRoot.findById(NodeId("b")))
        val instance = screenRoot.findById(s.selectedId!!)
        assertNotNull(instance)
        assertEquals(UserComponent.TYPE, instance.type)
        assertEquals(
            PropValue.Literal(JsonPrimitive(component.id)),
            instance.props[UserComponent.COMPONENT_ID_PROP],
        )
    }

    @Test
    fun `extract is one undoable step - undo restores the screen and drops the component`() {
        val s = state()
        s.select(NodeId("b"))
        s.extractSelectionToComponent("PrimaryButton")
        s.undo()

        assertTrue(s.document.components.isEmpty())
        assertEquals(root, s.activeScreen!!.root)
    }

    @Test
    fun `canExtractSelection is false for the root and with no selection, true for a child`() {
        val s = state()
        assertFalse(s.canExtractSelection) // nothing selected
        s.select(NodeId("root"))
        assertFalse(s.canExtractSelection) // the root cannot be extracted
        s.select(NodeId("b"))
        assertTrue(s.canExtractSelection)
    }

    @Test
    fun `extract is a no-op for an invalid or duplicate name`() {
        val s = state()
        s.select(NodeId("b"))
        s.extractSelectionToComponent("  ") // blank
        assertTrue(s.document.components.isEmpty())

        s.extractSelectionToComponent("PrimaryButton")
        s.select(NodeId("a"))
        s.extractSelectionToComponent("PrimaryButton") // duplicate name
        assertEquals(1, s.document.components.size)
    }

    @Test
    fun `componentNameError reports blank and duplicate names`() {
        val s = state()
        s.select(NodeId("b"))
        s.extractSelectionToComponent("PrimaryButton")
        assertNotNull(s.componentNameError(""))
        assertNotNull(s.componentNameError("PrimaryButton"))
        assertNull(s.componentNameError("SecondaryButton"))
    }

    @Test
    fun `the palette surfaces user components after extraction (P6a)`() {
        val s = state()
        assertEquals(s.catalog.palette.size, s.palette.size) // just the built-ins to begin with

        s.select(NodeId("b"))
        s.extractSelectionToComponent("PrimaryButton")

        val component = s.document.components.single()
        val entry = s.palette.single { it.componentId == component.id }
        assertEquals(UserComponent.TYPE, entry.type)
        assertEquals("PrimaryButton", entry.label)
        assertEquals(EditorState.USER_COMPONENTS_CATEGORY, entry.category)
    }

    @Test
    fun `adding a user-component palette entry inserts an instance referencing it`() {
        val s = state()
        s.select(NodeId("b"))
        s.extractSelectionToComponent("PrimaryButton")
        val component = s.document.components.single()
        val entry = s.palette.single { it.componentId == component.id }

        // Insert another instance of the component from the palette into the root.
        s.select(NodeId("root"))
        s.addFromPalette(entry)

        val inserted = s.activeScreen!!.root.findById(s.selectedId!!)!!
        assertEquals(UserComponent.TYPE, inserted.type)
        assertEquals(
            PropValue.Literal(JsonPrimitive(component.id)),
            inserted.props[UserComponent.COMPONENT_ID_PROP],
        )
    }
}

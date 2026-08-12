package viewforge.editor.state

import kotlinx.serialization.json.JsonPrimitive
import viewforge.command.RemoveComponent
import viewforge.model.ComponentDef
import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.model.findById
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The active edit surface (edit-in-place slice 2, #61): opening a component makes it — not the active
 * screen — the target of the canvas/tree/selection and of every node command, so a definition edit is a
 * live gesture. With no component open, everything behaves exactly as before (covered by the other
 * [EditorState] tests). No open gesture yet (slice 3), so these drive [openComponent] directly.
 */
class EditInPlaceTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.foundation.layout.Column", "Column", "Layout"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = type.startsWith("compose.foundation.layout.")

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private val screenText = Node(NodeId("s-text"), "compose.material3.Text")
    private val screenRoot = Node(NodeId("s-root"), "compose.foundation.layout.Column", children = listOf(screenText))
    private val compText = Node(NodeId("c-text"), "compose.material3.Text")
    private val compRoot = Node(NodeId("c-root"), "compose.foundation.layout.Box", children = listOf(compText))

    private fun state(): EditorState = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(Screen("s1", "Home", screenRoot)),
            components = listOf(ComponentDef(id = "c1", name = "PrimaryButton", root = compRoot)),
        ),
        FakeCatalog(),
    )

    @Test
    fun `with no component open the edit surface is the active screen`() {
        val s = state()
        assertNull(s.editingComponentId)
        assertEquals(screenRoot.id, s.activeEditRoot?.id)
        assertEquals("s1", s.activeEditRootId)
    }

    @Test
    fun `openComponent switches the edit surface to the component and clears selection`() {
        val s = state()
        s.select(NodeId("s-text"))
        s.openComponent("c1")

        assertEquals("c1", s.editingComponentId)
        assertEquals(compRoot.id, s.activeEditRoot?.id)
        assertEquals("c1", s.activeEditRootId)
        assertNull(s.selectedId) // selection reset on switch
    }

    @Test
    fun `openComponent with an unknown id is a no-op`() {
        val s = state()
        s.openComponent("nope")
        assertNull(s.editingComponentId)
        assertEquals("s1", s.activeEditRootId)
    }

    @Test
    fun `a node edit while a component is open targets the component, not the screen, and undoes`() {
        val s = state()
        s.openComponent("c1")
        s.select(NodeId("c-text"))
        s.setProp(NodeId("c-text"), "text", PropValue.Literal(JsonPrimitive("Hi")))

        // The component's tree changed; the screen is untouched.
        val editedComp = s.document.components.first { it.id == "c1" }
        assertEquals(
            PropValue.Literal(JsonPrimitive("Hi")),
            editedComp.root.findById(NodeId("c-text"))?.props?.get("text"),
        )
        assertEquals(screenRoot, s.document.screens.first().root)

        s.undo()
        assertEquals(compRoot, s.document.components.first { it.id == "c1" }.root)
    }

    @Test
    fun `selectedNode and delete resolve within the open component`() {
        val s = state()
        s.openComponent("c1")
        s.select(NodeId("c-text"))
        assertEquals(NodeId("c-text"), s.selectedNode?.id) // resolved against the component tree

        s.deleteSelected()
        assertNull(s.document.components.first { it.id == "c1" }.root.findById(NodeId("c-text")))
        assertTrue(s.document.screens.first().root.findById(NodeId("s-text")) != null) // screen intact
    }

    @Test
    fun `closeComponent returns to the screen and clears selection`() {
        val s = state()
        s.openComponent("c1")
        s.select(NodeId("c-text"))
        s.closeComponent()

        assertNull(s.editingComponentId)
        assertEquals(screenRoot.id, s.activeEditRoot?.id)
        assertNull(s.selectedId)
    }

    @Test
    fun `the edit surface falls back to the screen if the open component vanishes`() {
        val s = state()
        s.openComponent("c1")
        s.execute(RemoveComponent("c1")) // e.g. deleted elsewhere / undo of an extraction
        // editingComponentId still names c1, but it no longer resolves, so the surface is the screen again.
        assertEquals(screenRoot.id, s.activeEditRoot?.id)
        assertEquals("s1", s.activeEditRootId)
    }
}

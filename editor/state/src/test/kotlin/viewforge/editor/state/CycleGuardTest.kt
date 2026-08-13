package viewforge.editor.state

import viewforge.model.ComponentDef
import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import viewforge.model.UserComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Refusing cycle-forming instance inserts at edit time (#70). With a component open, inserting an
 * instance whose component would (directly or transitively) contain the one being edited is refused
 * up front — via the palette (click/drag) and paste — rather than only caught at render/load. Editing
 * a screen can never cycle, so those inserts are unaffected.
 */
class CycleGuardTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.foundation.layout.Column", "Column", "Layout"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = type.startsWith("compose.foundation.layout.")

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    // Component A (open for editing) and B, which already contains an instance of A — so inserting B into
    // A would close a cycle, but inserting A itself is the direct case.
    private val aRoot = Node(NodeId("a-root"), "compose.foundation.layout.Box")
    private val bRoot =
        Node(NodeId("b-root"), "compose.foundation.layout.Box", children = listOf(UserComponent.instance("a")))
    private val screenRoot = Node(NodeId("s-root"), "compose.foundation.layout.Column")

    private fun state(): EditorState = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(Screen("s1", "Home", screenRoot)),
            components = listOf(
                ComponentDef(id = "a", name = "A", root = aRoot),
                ComponentDef(id = "b", name = "B", root = bRoot),
            ),
        ),
        FakeCatalog(),
    )

    private fun instanceEntry(componentId: String) =
        PaletteEntry(UserComponent.TYPE, componentId, EditorState.USER_COMPONENTS_CATEGORY, componentId = componentId)

    @Test
    fun `paletteEntryWouldCycle only flags cycle-forming entries while a component is open`() {
        val s = state()
        // Editing a screen: nothing cycles.
        assertFalse(s.paletteEntryWouldCycle(instanceEntry("a")))

        s.openComponent("a")
        assertTrue(s.paletteEntryWouldCycle(instanceEntry("a"))) // A into A
        assertTrue(s.paletteEntryWouldCycle(instanceEntry("b"))) // B contains A -> A into B->A
        assertFalse(s.paletteEntryWouldCycle(PaletteEntry("compose.material3.Text", "Text", "Content"))) // built-in
    }

    @Test
    fun `addFromPalette refuses a cycle-forming insert`() {
        val s = state()
        s.openComponent("a")
        s.addFromPalette(instanceEntry("b"))
        assertTrue(s.document.components.first { it.id == "a" }.root.children.isEmpty())
        assertFalse(s.isDirty) // no command ran
    }

    @Test
    fun `addFromPalette still inserts a legal instance`() {
        val s = state()
        s.openComponent("b") // editing B; inserting A is legal (A references nothing)
        s.addFromPalette(instanceEntry("a"))
        // B started with one instance of A; a legal add makes two.
        assertEquals(2, s.document.components.first { it.id == "b" }.root.children.size)
    }

    @Test
    fun `paste refuses a cycle-forming instance and canPaste reflects it`() {
        val s = state()
        // Copy the instance of A that lives inside B onto the clipboard.
        s.openComponent("b")
        s.select(bRoot.children.first().id)
        s.copySelected()

        // Pasting that instance-of-A back into A would cycle: it must be refused and canPaste false.
        s.openComponent("a")
        assertFalse(s.canPaste)
        s.paste()
        assertTrue(s.document.components.first { it.id == "a" }.root.children.isEmpty())
    }
}

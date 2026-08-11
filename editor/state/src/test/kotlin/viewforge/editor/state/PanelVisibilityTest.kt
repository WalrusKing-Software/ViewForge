package viewforge.editor.state

import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Panel show/hide state (S1, #39). Each side panel starts visible and toggles independently; the
 * canvas has no flag because it is always shown. Transient chrome state — no document, no disk.
 */
class PanelVisibilityTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.material3.Text", "Text", "Content"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = false

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private fun state(): EditorState = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(Screen("s1", "Home", Node(NodeId("root"), "compose.material3.Text"))),
        ),
        FakeCatalog(),
    )

    @Test
    fun `all side panels start visible`() {
        val s = state()
        assertTrue(s.paletteVisible)
        assertTrue(s.treeVisible)
        assertTrue(s.inspectorVisible)
    }

    @Test
    fun `each toggle flips only its own panel`() {
        val s = state()

        s.togglePalette()
        assertFalse(s.paletteVisible)
        assertTrue(s.treeVisible)
        assertTrue(s.inspectorVisible)

        s.toggleTree()
        assertFalse(s.treeVisible)
        assertFalse(s.paletteVisible) // unchanged by the tree toggle
        assertTrue(s.inspectorVisible)

        s.toggleInspector()
        assertFalse(s.inspectorVisible)
        assertFalse(s.paletteVisible)
        assertFalse(s.treeVisible)
    }

    @Test
    fun `toggling twice returns to visible`() {
        val s = state()
        s.togglePalette()
        s.togglePalette()
        assertTrue(s.paletteVisible)
    }
}

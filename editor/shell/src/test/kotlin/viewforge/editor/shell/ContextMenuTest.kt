package viewforge.editor.shell

import viewforge.editor.state.ComponentCatalog
import viewforge.editor.state.EditorState
import viewforge.editor.state.PaletteEntry
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The right-click context menu is a thin view over existing [EditorState] actions (#160). These lock
 * down which actions it offers for a given state — the enablement in [contextMenuModel] — and the
 * right-click selection/open behaviour of [EditorState.requestContextMenu]. Pure model + state, no
 * composition or framework needed (mirrors [AppMenuBarTest]).
 */
class ContextMenuTest {
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

    private val root = Node(
        id = NodeId("root"),
        type = "compose.foundation.layout.Column",
        children = listOf(Node(NodeId("a"), "compose.material3.Text")),
    )

    private fun state(project: Project = defaultProject()): EditorState = EditorState(project, FakeCatalog())

    private fun defaultProject() = Project(
        id = "p",
        name = "P",
        framework = FrameworkRef("compose-multiplatform", "1.0.0"),
        screens = listOf(Screen("s1", "Home", root)),
    )

    @Test
    fun `nothing is offered without a selection, apart from paste which follows the clipboard`() {
        val m = state().contextMenuModel()
        assertFalse(m.hasSelection)
        assertFalse(m.canCutDelete)
        assertFalse(m.canDuplicate)
        assertFalse(m.canRename)
        assertFalse(m.canExtract)
        assertFalse(m.canEnterComponent)
        assertFalse(m.canPaste)
    }

    @Test
    fun `the root selection can be renamed but not cut, deleted, duplicated, or extracted`() {
        val s = state()
        s.select(NodeId("root"))
        val m = s.contextMenuModel()
        assertTrue(m.hasSelection)
        assertTrue(m.canRename) // renaming the screen root is allowed
        assertFalse(m.canCutDelete)
        assertFalse(m.canDuplicate)
        assertFalse(m.canExtract)
    }

    @Test
    fun `a non-root selection enables cut, delete, duplicate, extract, and rename`() {
        val s = state()
        s.select(NodeId("a"))
        val m = s.contextMenuModel()
        assertTrue(m.canCutDelete)
        assertTrue(m.canDuplicate)
        assertTrue(m.canExtract)
        assertTrue(m.canRename)
    }

    @Test
    fun `paste is gated on the clipboard`() {
        val s = state()
        s.select(NodeId("a"))
        assertFalse(s.contextMenuModel().canPaste)
        s.copySelected()
        assertTrue(s.contextMenuModel().canPaste)
    }

    @Test
    fun `rename is withheld when the tree is hidden since its inline editor lives there`() {
        val s = state()
        s.select(NodeId("a"))
        assertTrue(s.contextMenuModel().canRename)
        s.toggleTree() // hide the tree
        assertFalse(s.contextMenuModel().canRename)
    }

    @Test
    fun `enter component is offered only for a user-component instance`() {
        val instance = UserComponent.instance("cmp1")
        val screenRoot = Node(NodeId("root"), "compose.foundation.layout.Column", children = listOf(instance))
        val component = ComponentDef(id = "cmp1", name = "Comp1", root = Node(NodeId("cr"), "compose.material3.Text"))
        val s = state(
            defaultProject().copy(screens = listOf(Screen("s1", "Home", screenRoot)), components = listOf(component)),
        )
        s.select(NodeId("root"))
        assertFalse(s.contextMenuModel().canEnterComponent)
        s.select(instance.id)
        assertTrue(s.contextMenuModel().canEnterComponent)
    }

    @Test
    fun `requesting the menu selects the target and opens at the point`() {
        val s = state()
        s.requestContextMenu(NodeId("a"), 120f, 40f)
        assertTrue(s.contextMenuOpen)
        assertTrue(s.isSelected(NodeId("a")))
        s.dismissContextMenu()
        assertFalse(s.contextMenuOpen)
    }

    @Test
    fun `requesting the menu on a node already selected keeps the standing selection`() {
        val s = state()
        s.select(NodeId("a"))
        s.toggleSelection(NodeId("root")) // a multi-selection: a + root
        s.requestContextMenu(NodeId("a"), 0f, 0f)
        assertTrue(s.isSelected(NodeId("a")))
        assertTrue(s.isSelected(NodeId("root"))) // not collapsed to just the right-clicked node
    }

    @Test
    fun `a locked node is protected and opens no menu`() {
        val s = state()
        s.toggleLocked(NodeId("a"))
        s.requestContextMenu(NodeId("a"), 10f, 10f)
        assertFalse(s.contextMenuOpen)
        assertFalse(s.isSelected(NodeId("a")))
    }
}

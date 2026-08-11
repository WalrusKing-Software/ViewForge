package viewforge.editor.shell

import viewforge.editor.state.ComponentCatalog
import viewforge.editor.state.EditorState
import viewforge.editor.state.PaletteEntry
import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Edit menu is a thin view: each item's enabled-state must track the right [EditorState] flag.
 * These lock that mapping down (Paste ⇔ clipboard, the selection actions ⇔ a selection, undo/redo ⇔
 * history) so a future refactor can't silently wire, say, Paste to the wrong condition. Pure model
 * only — no composition or framework needed.
 */
class AppMenuBarTest {
    /** Column is a container; Text is a leaf. Enough for selection + one undoable edit. */
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
    fun `fresh document disables every stateful edit action`() {
        val model = state().editMenuModel()
        assertFalse(model.canUndo)
        assertFalse(model.canRedo)
        assertFalse(model.hasSelection)
        assertFalse(model.canPaste)
    }

    @Test
    fun `selecting a node enables the selection-scoped actions`() {
        val s = state()
        assertFalse(s.editMenuModel().hasSelection)
        s.select(NodeId("a"))
        assertTrue(s.editMenuModel().hasSelection)
    }

    @Test
    fun `paste is gated on the clipboard, not the selection`() {
        val s = state()
        s.select(NodeId("a"))
        assertFalse(s.editMenuModel().canPaste) // selection alone is not enough
        s.copySelected()
        assertTrue(s.editMenuModel().canPaste)
    }

    @Test
    fun `undo and redo track history across an edit`() {
        val s = state()
        s.select(NodeId("a"))
        s.deleteSelected()
        assertTrue(s.editMenuModel().canUndo)
        assertFalse(s.editMenuModel().canRedo)
        s.undo()
        assertTrue(s.editMenuModel().canRedo)
    }

    @Test
    fun `withAccel shows the shortcut in the label without a binding`() {
        assertEquals("Undo   (Ctrl+Z)", withAccel("Undo", "Ctrl+Z"))
    }

    @Test
    fun `save is disabled until there are unsaved edits`() {
        val s = state()
        assertFalse(s.fileMenuModel().canSave) // a fresh, clean document
        s.select(NodeId("a"))
        s.deleteSelected()
        assertTrue(s.fileMenuModel().canSave)
        s.markSaved(java.nio.file.Path.of("P.vforge"))
        assertFalse(s.fileMenuModel().canSave)
    }

    @Test
    fun `a fresh view can zoom either way but not reset`() {
        val model = state().viewMenuModel()
        assertTrue(model.canZoomIn)
        assertTrue(model.canZoomOut)
        assertFalse(model.canResetZoom) // already at the default 100% / origin
    }

    @Test
    fun `zooming enables reset`() {
        val s = state()
        s.zoomIn()
        assertTrue(s.viewMenuModel().canResetZoom)
        s.resetZoom()
        assertFalse(s.viewMenuModel().canResetZoom)
    }

    @Test
    fun `panning alone enables reset`() {
        val s = state()
        s.panBy(15f, 0f)
        assertTrue(s.viewMenuModel().canResetZoom)
    }
}

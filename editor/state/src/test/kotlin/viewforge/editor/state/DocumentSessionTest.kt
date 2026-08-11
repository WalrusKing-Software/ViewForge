package viewforge.editor.state

import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The document session (D1, #37): New / dirty-tracking / save-marking / whole-document replacement.
 * All in-memory — no disk — since [EditorState] only manages the session; the file I/O lives in the
 * shell's controller over `core/project` (itself round-trip-tested there).
 */
class DocumentSessionTest {
    /** Column is a container; Text is a leaf. Enough to exercise New's default root and one edit. */
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(
            PaletteEntry("compose.material3.Text", "Text", "Content"),
            PaletteEntry("compose.foundation.layout.Column", "Column", "Layout"),
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
    fun `a fresh session is clean and untitled`() {
        val s = state()
        assertFalse(s.isDirty)
        assertNull(s.currentPath)
    }

    @Test
    fun `an edit marks the document dirty`() {
        val s = state()
        s.select(NodeId("a"))
        s.deleteSelected()
        assertTrue(s.isDirty)
    }

    @Test
    fun `markSaved records the path and clears dirty`() {
        val s = state()
        s.select(NodeId("a"))
        s.deleteSelected()
        val path = Path.of("Home.vforge")
        s.markSaved(path)
        assertFalse(s.isDirty)
        assertEquals(path, s.currentPath)
    }

    @Test
    fun `undo still leaves the document dirty (safe direction)`() {
        val s = state()
        s.select(NodeId("a"))
        s.deleteSelected()
        s.markSaved(Path.of("Home.vforge"))
        s.undo()
        assertTrue(s.isDirty) // errs toward offering a redundant save, never dropping a real change
    }

    @Test
    fun `newDocument starts a blank, clean, untitled document with a container root`() {
        val s = state()
        s.select(NodeId("a"))
        s.deleteSelected()
        s.markSaved(Path.of("Home.vforge"))

        s.newDocument()

        assertFalse(s.isDirty)
        assertNull(s.currentPath)
        assertNull(s.selectedId)
        assertFalse(s.canUndo) // history was cleared with the old document
        val screen = s.activeScreen!!
        assertTrue(screen.root.children.isEmpty()) // an empty screen
        assertTrue(s.catalog.acceptsChildren(screen.root.type)) // rooted in a container
    }

    @Test
    fun `newDocument keeps the current framework`() {
        val s = state()
        val framework = s.document.framework
        s.newDocument()
        assertEquals(framework, s.document.framework)
    }

    @Test
    fun `replaceDocument swaps the document and resets the session`() {
        val s = state()
        s.select(NodeId("a"))
        s.deleteSelected() // dirties and pushes history

        val opened = Project(
            id = "q",
            name = "Opened",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(Screen("s2", "Second", Node(NodeId("r2"), "compose.foundation.layout.Column"))),
        )
        val path = Path.of("Opened.vforge")
        s.replaceDocument(opened, path)

        assertEquals(opened, s.document)
        assertEquals("s2", s.activeScreenId)
        assertEquals(path, s.currentPath)
        assertFalse(s.isDirty)
        assertNull(s.selectedId)
        assertFalse(s.canUndo)
    }
}

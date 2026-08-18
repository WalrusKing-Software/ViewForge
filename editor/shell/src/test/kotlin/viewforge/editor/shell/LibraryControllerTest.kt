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
import viewforge.model.findById
import viewforge.project.ComponentLibraryStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The library controller (ADR-033, #209) glues [EditorState]'s pure library logic to the on-disk
 * [ComponentLibraryStore]. What needs pinning here is the round trip through a real (temp) store folder:
 * add/remove/rename persist and reload into the palette, the self-contained-only gate is enforced, and the
 * insert routing opens the name prompt only on a collision. No composition — the controller methods and the
 * store do the work.
 */
class LibraryControllerTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.foundation.layout.Box", "Box", "Layout"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = true

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private val screenRoot = Node(NodeId("root"), "compose.foundation.layout.Box")

    /** A leaf component (self-contained) and one that references it (must be refused for the library). */
    private val leaf = ComponentDef("cmp_leaf", "Card", root = Node(NodeId("c"), "compose.material3.Text"))
    private val wrapper = ComponentDef(
        "cmp_wrap",
        "Wrapper",
        root = Node(
            NodeId("w"),
            "compose.foundation.layout.Box",
            children = listOf(UserComponent.instance("cmp_leaf", NodeId("inst"))),
        ),
    )

    private fun state(): EditorState = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(Screen("s1", "Home", screenRoot)),
            components = listOf(leaf, wrapper),
        ),
        FakeCatalog(),
    )

    private fun tempDir(): Path = Files.createTempDirectory("viewforge-library-controller-test")

    @Test
    fun `addToLibrary persists a self-contained component and reload surfaces it in the palette`() {
        val s = state()
        val dir = tempDir()
        val c = LibraryController(s, dir)

        c.addToLibrary("cmp_leaf")

        val entry = s.palette.single { it.libraryId != null }
        assertEquals("Card", entry.label)
        assertEquals(EditorState.LIBRARY_CATEGORY, entry.category)
        // Persisted under a fresh global id, not the document component id.
        assertEquals(1, ComponentLibraryStore.list(dir).size)
        assertFalse(entry.libraryId == "cmp_leaf")
    }

    @Test
    fun `addToLibrary refuses a component that references others`() {
        val s = state()
        val dir = tempDir()
        val c = LibraryController(s, dir)

        c.addToLibrary("cmp_wrap")

        assertTrue(s.libraryComponents.isEmpty())
        assertEquals(0, ComponentLibraryStore.list(dir).size)
    }

    @Test
    fun `reload loads an existing store folder at startup`() {
        val dir = tempDir()
        ComponentLibraryStore.save(
            ComponentDef("lib_x", "Banner", root = Node(NodeId("n"), "compose.material3.Text")),
            dir,
        )
        val s = state()
        val c = LibraryController(s, dir)

        c.reload()
        assertEquals(listOf("Banner"), s.libraryComponents.map { it.name })
    }

    @Test
    fun `removeFromLibrary deletes the entry`() {
        val s = state()
        val dir = tempDir()
        val c = LibraryController(s, dir)
        c.addToLibrary("cmp_leaf")
        val id = s.libraryComponents.single().id

        c.removeFromLibrary(id)
        assertTrue(s.libraryComponents.isEmpty())
        assertEquals(0, ComponentLibraryStore.list(dir).size)
    }

    @Test
    fun `renameInLibrary renames a valid, unique name and ignores an invalid one`() {
        val s = state()
        val dir = tempDir()
        val c = LibraryController(s, dir)
        c.addToLibrary("cmp_leaf")
        val id = s.libraryComponents.single().id

        c.renameInLibrary(id, "") // invalid — ignored
        assertEquals("Card", s.libraryComponents.single().name)

        c.renameInLibrary(id, "Panel")
        assertEquals("Panel", s.libraryComponents.single().name)
    }

    @Test
    fun `insert routes a clean library entry straight in but prompts on a name collision`() {
        val s = state()
        val dir = tempDir()
        val c = LibraryController(s, dir)
        c.addToLibrary("cmp_leaf") // library now has 'Card' (document already has a 'Card' component)
        val entry = s.palette.single { it.libraryId != null }

        // 'Card' already names a document component (cmp_leaf), so the insert must prompt.
        c.insert(entry)
        assertNotNull(c.insertPrompt)

        c.confirmInsert(s.suggestedLibraryName(entry))
        assertNull(c.insertPrompt)
        // A copy was added to the document and an instance dropped on the screen.
        assertEquals(3, s.document.components.size) // leaf + wrapper + the inserted copy
        val instance = s.activeScreen!!.root.findById(s.selectedId!!)!!
        assertEquals(UserComponent.TYPE, instance.type)
    }

    @Test
    fun `insert of a non-colliding library entry copies in with no prompt`() {
        val s = state()
        val dir = tempDir()
        // A library entry whose name does NOT collide with any document component.
        ComponentLibraryStore.save(
            ComponentDef("lib_u", "Unique", root = Node(NodeId("n"), "compose.material3.Text")),
            dir,
        )
        val c = LibraryController(s, dir)
        c.reload()
        val entry = s.palette.single { it.libraryId == "lib_u" }

        c.insert(entry)
        assertNull(c.insertPrompt) // no collision → straight in
        assertTrue(s.document.components.any { it.name == "Unique" })
    }
}

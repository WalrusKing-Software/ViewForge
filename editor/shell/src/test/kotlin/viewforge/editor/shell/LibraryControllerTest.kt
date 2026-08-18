package viewforge.editor.shell

import viewforge.editor.state.ComponentCatalog
import viewforge.editor.state.EditorState
import viewforge.editor.state.PaletteEntry
import viewforge.model.ChildAddress
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
import viewforge.project.LibraryComponent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The library controller (ADR-033, #209; closure + drag #234) glues [EditorState]'s pure library logic to the
 * on-disk [ComponentLibraryStore]. What needs pinning here is the round trip through a real (temp) store
 * folder: add/remove/rename persist and reload into the palette, a nested component bundles its closure, a
 * dangling reference is refused, and insert routing (click *and* drag) opens the name prompt only on a
 * collision. No composition — the controller methods and the store do the work.
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

    /** A leaf component (self-contained) and one that references it (a resolvable nested component). */
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

    private fun state(components: List<ComponentDef> = listOf(leaf, wrapper)): EditorState = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(Screen("s1", "Home", screenRoot)),
            components = components,
        ),
        FakeCatalog(),
    )

    private fun tempDir(): Path = Files.createTempDirectory("viewforge-library-controller-test")

    private fun entry(comp: ComponentDef) = LibraryComponent(component = comp)

    @Test
    fun `addToLibrary persists a self-contained component and reload surfaces it in the palette`() {
        val s = state()
        val dir = tempDir()
        val c = LibraryController(s, dir)

        c.addToLibrary("cmp_leaf")

        val e = s.palette.single { it.libraryId != null }
        assertEquals("Card", e.label)
        assertEquals(EditorState.LIBRARY_CATEGORY, e.category)
        // Persisted under a fresh global id, not the document component id.
        assertEquals(1, ComponentLibraryStore.list(dir).size)
        assertFalse(e.libraryId == "cmp_leaf")
    }

    @Test
    fun `addToLibrary bundles a nested component's dependency closure`() {
        val s = state()
        val dir = tempDir()
        val c = LibraryController(s, dir)

        c.addToLibrary("cmp_wrap") // Wrapper references Card (resolvable) — now bundled, not refused

        val stored = ComponentLibraryStore.list(dir).single()
        assertEquals("Wrapper", stored.component.name)
        assertEquals(listOf("Card"), stored.dependencies.map { it.name })
        assertEquals(1, s.libraryComponents.size)
    }

    @Test
    fun `addToLibrary refuses a component with a dangling reference`() {
        val dangling = ComponentDef(
            "cmp_bad",
            "Bad",
            root = Node(
                NodeId("bd"),
                "compose.foundation.layout.Box",
                children = listOf(UserComponent.instance("cmp_ghost", NodeId("g"))),
            ),
        )
        val s = state(components = listOf(dangling))
        val dir = tempDir()
        val c = LibraryController(s, dir)

        c.addToLibrary("cmp_bad")

        assertTrue(s.libraryComponents.isEmpty())
        assertEquals(0, ComponentLibraryStore.list(dir).size)
    }

    @Test
    fun `reload loads an existing store folder at startup`() {
        val dir = tempDir()
        ComponentLibraryStore.save(
            entry(ComponentDef("lib_x", "Banner", root = Node(NodeId("n"), "compose.material3.Text"))),
            dir,
        )
        val s = state()
        val c = LibraryController(s, dir)

        c.reload()
        assertEquals(listOf("Banner"), s.libraryComponents.map { it.component.name })
    }

    @Test
    fun `removeFromLibrary deletes the entry`() {
        val s = state()
        val dir = tempDir()
        val c = LibraryController(s, dir)
        c.addToLibrary("cmp_leaf")
        val id = s.libraryComponents.single().component.id

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
        val id = s.libraryComponents.single().component.id

        c.renameInLibrary(id, "") // invalid — ignored
        assertEquals("Card", s.libraryComponents.single().component.name)

        c.renameInLibrary(id, "Panel")
        assertEquals("Panel", s.libraryComponents.single().component.name)
    }

    @Test
    fun `insert routes a clean library entry straight in but prompts on a name collision`() {
        val s = state()
        val dir = tempDir()
        val c = LibraryController(s, dir)
        c.addToLibrary("cmp_leaf") // library now has 'Card' (document already has a 'Card' component)
        val e = s.palette.single { it.libraryId != null }

        // 'Card' already names a document component (cmp_leaf), so the insert must prompt.
        c.insert(e)
        assertNotNull(c.insertPrompt)

        c.confirmInsert(s.suggestedLibraryName(e))
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
            entry(ComponentDef("lib_u", "Unique", root = Node(NodeId("n"), "compose.material3.Text"))),
            dir,
        )
        val c = LibraryController(s, dir)
        c.reload()
        val e = s.palette.single { it.libraryId == "lib_u" }

        c.insert(e)
        assertNull(c.insertPrompt) // no collision → straight in
        assertTrue(s.document.components.any { it.name == "Unique" })
    }

    @Test
    fun `dropDrag inserts a clean library entry at the resolved drop address and consumes the drag`() {
        val s = state()
        val dir = tempDir()
        ComponentLibraryStore.save(
            entry(ComponentDef("lib_u", "Unique", root = Node(NodeId("n"), "compose.material3.Text"))),
            dir,
        )
        val c = LibraryController(s, dir)
        c.reload()
        val e = s.palette.single { it.libraryId == "lib_u" }

        s.beginPaletteDrag(e)
        s.resolvePaletteDrop(ChildAddress(NodeId("root"), null, 0))
        c.dropDrag(e)

        assertNull(c.insertPrompt) // no collision → straight in, no prompt
        assertTrue(s.document.components.any { it.name == "Unique" })
        assertEquals(UserComponent.TYPE, s.activeScreen!!.root.children.first().type) // landed at the drop position
        assertNull(s.paletteDragType) // drag consumed
    }

    @Test
    fun `dropDrag opens the name prompt on a collision and inserts at the remembered position`() {
        val s = state()
        val dir = tempDir()
        val c = LibraryController(s, dir)
        c.addToLibrary("cmp_leaf") // library 'Card' collides with the document's 'Card'
        val e = s.palette.single { it.libraryId != null }

        s.beginPaletteDrag(e)
        s.resolvePaletteDrop(ChildAddress(NodeId("root"), null, 0))
        c.dropDrag(e)
        assertNotNull(c.insertPrompt) // collision → prompt, drag already consumed
        assertNull(s.paletteDragType)

        c.confirmInsert(s.suggestedLibraryName(e))
        assertNull(c.insertPrompt)
        // Inserted at the remembered drop position (root's first child), not the selection.
        assertEquals(UserComponent.TYPE, s.activeScreen!!.root.children.first().type)
    }
}

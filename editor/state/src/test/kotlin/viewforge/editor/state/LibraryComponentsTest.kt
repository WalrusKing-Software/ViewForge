package viewforge.editor.state

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ComponentDef
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
 * Cross-project component library behaviour of [EditorState] (ADR-033, #209): library components surface
 * in the palette as their own source, inserting one *copies* its definition into the document (never a
 * live reference), collisions defer to a caller-supplied name, and a component that references others is
 * refused for the library. A [FakeCatalog] stands in for the Compose package so these run without a
 * composition.
 */
class LibraryComponentsTest {
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

    /** A self-contained library component: a Box holding a single Text, referencing nothing. */
    private fun libComponent(id: String, name: String): ComponentDef = ComponentDef(
        id = id,
        name = name,
        root = Node(
            id = NodeId("lib_root"),
            type = "compose.foundation.layout.Box",
            children = listOf(Node(NodeId("lib_text"), "compose.material3.Text")),
        ),
    )

    @Test
    fun `library components surface in the palette under the Library category`() {
        val s = state()
        s.applyLibraryComponents(listOf(libComponent("lib_1", "PrimaryButton")))

        val entry = s.palette.single { it.libraryId == "lib_1" }
        assertEquals(UserComponent.TYPE, entry.type)
        assertEquals("PrimaryButton", entry.label)
        assertEquals(EditorState.LIBRARY_CATEGORY, entry.category)
        assertNull(entry.componentId)
    }

    @Test
    fun `applyLibraryComponents sorts by name for a stable palette order`() {
        val s = state()
        s.applyLibraryComponents(
            listOf(libComponent("l2", "Beta"), libComponent("l1", "alpha"), libComponent("l3", "Gamma")),
        )
        assertEquals(listOf("alpha", "Beta", "Gamma"), s.libraryComponents.map { it.name })
    }

    @Test
    fun `inserting a library component copies the definition in with fresh ids and drops a referencing instance`() {
        val s = state()
        s.applyLibraryComponents(listOf(libComponent("lib_1", "PrimaryButton")))
        val entry = s.palette.single { it.libraryId == "lib_1" }

        s.select(NodeId("root"))
        s.insertLibraryComponent(entry, "PrimaryButton")

        // A document component was created — a *copy*, not the library id, with fresh node ids.
        val copied = s.document.components.single()
        assertEquals("PrimaryButton", copied.name)
        assertFalse(copied.id == "lib_1") // fresh document component id
        assertFalse(copied.root.id == NodeId("lib_root")) // withFreshIds() reassigned node ids

        // The insertion point holds an instance referencing the copy.
        val instance = s.activeScreen!!.root.findById(s.selectedId!!)!!
        assertEquals(UserComponent.TYPE, instance.type)
        assertEquals(
            PropValue.Literal(JsonPrimitive(copied.id)),
            instance.props[UserComponent.COMPONENT_ID_PROP],
        )
    }

    @Test
    fun `inserting a library component is one undoable step`() {
        val s = state()
        s.applyLibraryComponents(listOf(libComponent("lib_1", "PrimaryButton")))
        val entry = s.palette.single { it.libraryId == "lib_1" }
        s.select(NodeId("root"))
        s.insertLibraryComponent(entry, "PrimaryButton")

        s.undo()
        assertTrue(s.document.components.isEmpty())
        assertEquals(root, s.activeScreen!!.root)
    }

    @Test
    fun `a colliding name needs a prompt and a free name is suggested`() {
        val s = state()
        // Give the document its own component named PrimaryButton.
        s.applyLibraryComponents(listOf(libComponent("lib_1", "PrimaryButton")))
        val entry = s.palette.single { it.libraryId == "lib_1" }
        s.select(NodeId("root"))
        s.insertLibraryComponent(entry, "PrimaryButton") // now the doc has PrimaryButton

        assertTrue(s.libraryInsertNeedsName(entry)) // second insert would collide
        val suggestion = s.suggestedLibraryName(entry)
        assertNull(s.componentNameError(suggestion)) // the suggestion is itself free & legal

        s.insertLibraryComponent(entry, suggestion)
        assertEquals(2, s.document.components.size)
        assertTrue(s.document.components.map { it.name }.contains(suggestion))
    }

    @Test
    fun `a non-colliding insert needs no name`() {
        val s = state()
        s.applyLibraryComponents(listOf(libComponent("lib_1", "PrimaryButton")))
        val entry = s.palette.single { it.libraryId == "lib_1" }
        assertFalse(s.libraryInsertNeedsName(entry))
        assertEquals("PrimaryButton", s.suggestedLibraryName(entry))
    }

    @Test
    fun `addFromPalette refuses a library entry - it must go through insertLibraryComponent`() {
        val s = state()
        s.applyLibraryComponents(listOf(libComponent("lib_1", "PrimaryButton")))
        val entry = s.palette.single { it.libraryId == "lib_1" }
        s.select(NodeId("root"))
        s.addFromPalette(entry)

        assertTrue(s.document.components.isEmpty()) // nothing copied
        assertEquals(root, s.activeScreen!!.root) // nothing inserted
    }

    @Test
    fun `libraryNameError and uniqueLibraryName enforce legal, unique library names`() {
        val s = state()
        s.applyLibraryComponents(listOf(libComponent("lib_1", "Card")))

        assertNull(s.libraryNameError("Button")) // free and legal
        assertNotNull(s.libraryNameError("")) // blank
        assertNotNull(s.libraryNameError("Card")) // duplicate in library
        assertNull(s.libraryNameError("Card", excludingId = "lib_1")) // renaming to its own name is fine

        assertEquals("Card2", s.uniqueLibraryName("Card")) // disambiguated
        assertEquals("Button", s.uniqueLibraryName("Button")) // free stays as-is
    }

    @Test
    fun `libraryAddBlockReason permits a self-contained component and refuses one that references others`() {
        // Seed two document components: a leaf, and one whose tree references the leaf.
        val leaf = libComponent("cmp_leaf", "Leaf")
        val references = ComponentDef(
            id = "cmp_wrap",
            name = "Wrapper",
            root = Node(
                id = NodeId("w"),
                type = "compose.foundation.layout.Box",
                children = listOf(UserComponent.instance("cmp_leaf", NodeId("inst"))),
            ),
        )
        val s = EditorState(
            Project(
                id = "p",
                name = "P",
                framework = FrameworkRef("compose-multiplatform", "1.0.0"),
                screens = listOf(Screen("s1", "Home", root)),
                components = listOf(leaf, references),
            ),
            FakeCatalog(),
        )

        assertNull(s.libraryAddBlockReason("cmp_leaf")) // self-contained → allowed
        assertNotNull(s.libraryAddBlockReason("cmp_wrap")) // references another → refused
        assertNotNull(s.libraryAddBlockReason("missing")) // unknown → refused
    }
}

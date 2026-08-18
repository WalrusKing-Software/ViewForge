package viewforge.editor.state

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ChildAddress
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
import viewforge.model.referencedComponentIds
import viewforge.project.LibraryComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-project component library behaviour of [EditorState] (ADR-033, #209; closure bundling #234): library
 * entries surface in the palette as their own source, inserting one *copies* its whole bundle into the
 * document (never a live reference) with fresh ids, collisions defer to a caller-supplied name, a nested
 * component travels with its dependency closure, and only a *dangling* reference is refused for the library.
 * A [FakeCatalog] stands in for the Compose package so these run without a composition.
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

    private fun state(components: List<ComponentDef> = emptyList()): EditorState = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(Screen("s1", "Home", root)),
            components = components,
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

    private fun entry(comp: ComponentDef, dependencies: List<ComponentDef> = emptyList()) =
        LibraryComponent(component = comp, dependencies = dependencies)

    @Test
    fun `library components surface in the palette under the Library category`() {
        val s = state()
        s.applyLibraryComponents(listOf(entry(libComponent("lib_1", "PrimaryButton"))))

        val e = s.palette.single { it.libraryId == "lib_1" }
        assertEquals(UserComponent.TYPE, e.type)
        assertEquals("PrimaryButton", e.label)
        assertEquals(EditorState.LIBRARY_CATEGORY, e.category)
        assertNull(e.componentId)
    }

    @Test
    fun `applyLibraryComponents sorts by name for a stable palette order`() {
        val s = state()
        s.applyLibraryComponents(
            listOf(
                entry(libComponent("l2", "Beta")),
                entry(libComponent("l1", "alpha")),
                entry(libComponent("l3", "Gamma")),
            ),
        )
        assertEquals(listOf("alpha", "Beta", "Gamma"), s.libraryComponents.map { it.component.name })
    }

    @Test
    fun `inserting a library component copies the definition in with fresh ids and drops a referencing instance`() {
        val s = state()
        s.applyLibraryComponents(listOf(entry(libComponent("lib_1", "PrimaryButton"))))
        val e = s.palette.single { it.libraryId == "lib_1" }

        s.select(NodeId("root"))
        s.insertLibraryComponent(e, "PrimaryButton")

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
    fun `inserting a nested library component copies the whole closure, self-contained with rewritten refs`() {
        val s = state()
        // A bundle: primary 'Card' references its dependency 'Leaf' (by the library-side id 'lib_leaf').
        val leaf = libComponent("lib_leaf", "Leaf")
        val card = ComponentDef(
            id = "lib_card",
            name = "Card",
            root = Node(
                id = NodeId("card_root"),
                type = "compose.foundation.layout.Box",
                children = listOf(UserComponent.instance("lib_leaf", NodeId("card_inst"))),
            ),
        )
        s.applyLibraryComponents(listOf(entry(card, dependencies = listOf(leaf))))
        val e = s.palette.single { it.libraryId == "lib_card" }

        s.select(NodeId("root"))
        s.insertLibraryComponent(e, "Card")

        // Both the primary and its dependency were copied in, under fresh ids (neither library id survives).
        assertEquals(2, s.document.components.size)
        val cardCopy = s.document.components.single { it.name == "Card" }
        val leafCopy = s.document.components.single { it.name == "Leaf" }
        assertFalse(cardCopy.id == "lib_card")
        assertFalse(leafCopy.id == "lib_leaf")

        // The primary's instance was repointed at the dependency *copy* — self-contained, no dangling 'lib_leaf'.
        assertEquals(setOf(leafCopy.id), cardCopy.root.referencedComponentIds())
        // The dropped instance references the primary copy.
        val instance = s.activeScreen!!.root.findById(s.selectedId!!)!!
        assertEquals(PropValue.Literal(JsonPrimitive(cardCopy.id)), instance.props[UserComponent.COMPONENT_ID_PROP])
    }

    @Test
    fun `a dependency whose name collides with a document component is silently uniquified`() {
        // The document already has a component named 'Leaf'; the inserted closure carries its own 'Leaf'.
        val existing = libComponent("cmp_existing", "Leaf")
        val s = state(components = listOf(existing))
        val leaf = libComponent("lib_leaf", "Leaf")
        val card = ComponentDef(
            id = "lib_card",
            name = "Card",
            root = Node(
                id = NodeId("card_root"),
                type = "compose.foundation.layout.Box",
                children = listOf(UserComponent.instance("lib_leaf", NodeId("card_inst"))),
            ),
        )
        s.applyLibraryComponents(listOf(entry(card, dependencies = listOf(leaf))))
        val e = s.palette.single { it.libraryId == "lib_card" }

        s.select(NodeId("root"))
        s.insertLibraryComponent(e, "Card")

        // The dependency copy took a disambiguated name and the primary references *it*, not the pre-existing 'Leaf'.
        val leaf2 = s.document.components.single { it.name == "Leaf2" }
        val cardCopy = s.document.components.single { it.name == "Card" }
        assertEquals(setOf(leaf2.id), cardCopy.root.referencedComponentIds())
        assertFalse(leaf2.id == existing.id)
    }

    @Test
    fun `inserting a library component is one undoable step`() {
        val s = state()
        val leaf = libComponent("lib_leaf", "Leaf")
        val card = ComponentDef(
            id = "lib_card",
            name = "Card",
            root = Node(
                id = NodeId("card_root"),
                type = "compose.foundation.layout.Box",
                children = listOf(UserComponent.instance("lib_leaf", NodeId("card_inst"))),
            ),
        )
        s.applyLibraryComponents(listOf(entry(card, dependencies = listOf(leaf))))
        val e = s.palette.single { it.libraryId == "lib_card" }
        s.select(NodeId("root"))
        s.insertLibraryComponent(e, "Card")

        s.undo()
        assertTrue(s.document.components.isEmpty()) // both the primary and the dependency reverted together
        assertEquals(root, s.activeScreen!!.root)
    }

    @Test
    fun `inserting at an explicit target lands the instance at that position, not the selection`() {
        val s = state()
        s.applyLibraryComponents(listOf(entry(libComponent("lib_1", "PrimaryButton"))))
        val e = s.palette.single { it.libraryId == "lib_1" }

        // Drop it as the first child of the root, regardless of the current selection.
        s.insertLibraryComponent(e, "PrimaryButton", target = ChildAddress(NodeId("root"), null, 0))

        val first = s.activeScreen!!.root.children.first()
        assertEquals(UserComponent.TYPE, first.type)
        assertEquals(s.selectedId, first.id)
    }

    @Test
    fun `a colliding name needs a prompt and a free name is suggested`() {
        val s = state()
        s.applyLibraryComponents(listOf(entry(libComponent("lib_1", "PrimaryButton"))))
        val e = s.palette.single { it.libraryId == "lib_1" }
        s.select(NodeId("root"))
        s.insertLibraryComponent(e, "PrimaryButton") // now the doc has PrimaryButton

        assertTrue(s.libraryInsertNeedsName(e)) // second insert would collide
        val suggestion = s.suggestedLibraryName(e)
        assertNull(s.componentNameError(suggestion)) // the suggestion is itself free & legal

        s.insertLibraryComponent(e, suggestion)
        assertEquals(2, s.document.components.size)
        assertTrue(s.document.components.map { it.name }.contains(suggestion))
    }

    @Test
    fun `a non-colliding insert needs no name`() {
        val s = state()
        s.applyLibraryComponents(listOf(entry(libComponent("lib_1", "PrimaryButton"))))
        val e = s.palette.single { it.libraryId == "lib_1" }
        assertFalse(s.libraryInsertNeedsName(e))
        assertEquals("PrimaryButton", s.suggestedLibraryName(e))
    }

    @Test
    fun `addFromPalette refuses a library entry - it must go through insertLibraryComponent`() {
        val s = state()
        s.applyLibraryComponents(listOf(entry(libComponent("lib_1", "PrimaryButton"))))
        val e = s.palette.single { it.libraryId == "lib_1" }
        s.select(NodeId("root"))
        s.addFromPalette(e)

        assertTrue(s.document.components.isEmpty()) // nothing copied
        assertEquals(root, s.activeScreen!!.root) // nothing inserted
    }

    @Test
    fun `libraryNameError and uniqueLibraryName enforce legal, unique library names`() {
        val s = state()
        s.applyLibraryComponents(listOf(entry(libComponent("lib_1", "Card"))))

        assertNull(s.libraryNameError("Button")) // free and legal
        assertNotNull(s.libraryNameError("")) // blank
        assertNotNull(s.libraryNameError("Card")) // duplicate in library
        assertNull(s.libraryNameError("Card", excludingId = "lib_1")) // renaming to its own name is fine

        assertEquals("Card2", s.uniqueLibraryName("Card")) // disambiguated
        assertEquals("Button", s.uniqueLibraryName("Button")) // free stays as-is
    }

    @Test
    fun `libraryAddBlockReason permits self-contained and nested-resolvable, refuses a dangling reference`() {
        // Seed three document components: a leaf, one that references the leaf (resolvable), and one that
        // references a component that does not exist (dangling).
        val leaf = libComponent("cmp_leaf", "Leaf")
        val nested = ComponentDef(
            id = "cmp_wrap",
            name = "Wrapper",
            root = Node(
                id = NodeId("w"),
                type = "compose.foundation.layout.Box",
                children = listOf(UserComponent.instance("cmp_leaf", NodeId("inst"))),
            ),
        )
        val dangling = ComponentDef(
            id = "cmp_broken",
            name = "Broken",
            root = Node(
                id = NodeId("b"),
                type = "compose.foundation.layout.Box",
                children = listOf(UserComponent.instance("cmp_ghost", NodeId("ginst"))),
            ),
        )
        val s = state(components = listOf(leaf, nested, dangling))

        assertNull(s.libraryAddBlockReason("cmp_leaf")) // self-contained → allowed
        assertNull(s.libraryAddBlockReason("cmp_wrap")) // nested but resolvable → now allowed (#234)
        assertNotNull(s.libraryAddBlockReason("cmp_broken")) // dangling reference → refused
        assertNotNull(s.libraryAddBlockReason("missing")) // unknown → refused
    }
}

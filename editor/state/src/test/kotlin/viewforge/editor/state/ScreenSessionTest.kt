package viewforge.editor.state

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Screen naming & multi-screen session behaviour (D6): add/remove/rename go through commands (so they
 * undo), the active screen is maintained across them, and screen names are validated at edit time —
 * both as legal identifiers (via the catalog, GC-3) and for uniqueness. UI gestures are out of scope
 * here, as with the other [EditorState] tests.
 */
class ScreenSessionTest {
    /**
     * A minimal catalog: Column is the sole container (so [EditorState.addScreen] can root a new screen)
     * and screen names must be simple identifiers — enough to exercise the invalid-name branch.
     */
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.foundation.layout.Column", "Column", "Layout"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = type.startsWith("compose.foundation.layout.")

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null

        // A legal identifier: letter/underscore start, then letters/digits/underscore. Good enough to
        // reject blanks, spaces and leading digits in these tests.
        override fun isValidScreenName(name: String): Boolean = Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)
    }

    private fun state(vararg screens: Screen): EditorState = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = screens.toList(),
        ),
        FakeCatalog(),
    )

    private fun screen(id: String, name: String) =
        Screen(id, name, Node(NodeId("$id-root"), "compose.foundation.layout.Column"))

    private fun oneScreen() = state(screen("s1", "Home"))

    // --- add ------------------------------------------------------------------------------------

    @Test
    fun `addScreen appends a uniquely named screen and makes it active`() {
        val s = oneScreen()
        s.addScreen()
        assertEquals(2, s.document.screens.size)
        val added = s.document.screens.last()
        assertEquals(added.id, s.activeScreenId)
        // Auto-name is a legal identifier and not a duplicate.
        assertTrue(s.catalog.isValidScreenName(added.name))
        assertNull(s.screenNameError(added.name, excludingId = added.id))
    }

    @Test
    fun `addScreen avoids colliding with an existing Screen-n name`() {
        val s = state(screen("s1", "Screen1"))
        s.addScreen()
        assertEquals(setOf("Screen1", "Screen2"), s.document.screens.map { it.name }.toSet())
    }

    @Test
    fun `addScreen is undoable`() {
        val s = oneScreen()
        s.addScreen()
        s.undo()
        assertEquals(1, s.document.screens.size)
    }

    // --- remove ---------------------------------------------------------------------------------

    @Test
    fun `removeScreen drops the screen and reselects a neighbour when the active one goes`() {
        val s = state(screen("s1", "Home"), screen("s2", "Details"))
        s.activeScreenId = "s2"
        s.removeScreen("s2")
        assertEquals(listOf("s1"), s.document.screens.map { it.id })
        assertEquals("s1", s.activeScreenId)
    }

    @Test
    fun `removeScreen refuses to remove the only screen`() {
        val s = oneScreen()
        s.removeScreen("s1")
        assertEquals(1, s.document.screens.size)
    }

    @Test
    fun `removeScreen is undoable`() {
        val s = state(screen("s1", "Home"), screen("s2", "Details"))
        s.removeScreen("s1")
        s.undo()
        assertEquals(listOf("s1", "s2"), s.document.screens.map { it.id })
    }

    // --- rename ---------------------------------------------------------------------------------

    @Test
    fun `renameScreen sets a valid name and is undoable without touching structure`() {
        val s = oneScreen()
        val rootBefore = s.document.screens[0].root
        s.renameScreen("s1", "Landing")
        assertEquals("Landing", s.document.screens[0].name)
        assertEquals(rootBefore, s.document.screens[0].root)
        s.undo()
        assertEquals("Home", s.document.screens[0].name)
    }

    @Test
    fun `renameScreen trims surrounding whitespace`() {
        val s = oneScreen()
        s.renameScreen("s1", "  Landing  ")
        assertEquals("Landing", s.document.screens[0].name)
    }

    @Test
    fun `renameScreen rejects an invalid identifier as a no-op`() {
        val s = oneScreen()
        s.renameScreen("s1", "Home Screen") // space → not a legal identifier
        assertEquals("Home", s.document.screens[0].name)
        assertFalse(s.canUndo) // nothing was executed
    }

    @Test
    fun `renameScreen rejects a duplicate name as a no-op`() {
        val s = state(screen("s1", "Home"), screen("s2", "Details"))
        s.renameScreen("s2", "Home")
        assertEquals("Details", s.document.screens.first { it.id == "s2" }.name)
    }

    // --- validation -----------------------------------------------------------------------------

    @Test
    fun `screenNameError reports blank, invalid, duplicate, and accepts a fresh valid name`() {
        val s = state(screen("s1", "Home"), screen("s2", "Details"))
        assertNotNull(s.screenNameError("   ", excludingId = null))
        assertNotNull(s.screenNameError("2Bad", excludingId = null)) // leading digit
        assertNotNull(s.screenNameError("Details", excludingId = "s1")) // taken by s2
        assertNull(s.screenNameError("Details", excludingId = "s2")) // its own name is fine
        assertNull(s.screenNameError("Profile", excludingId = null))
    }

    // --- node F2 bridge -------------------------------------------------------------------------

    @Test
    fun `requestRenameSelected targets the selection and clears on consume`() {
        val s = oneScreen()
        s.select(NodeId("s1-root"))
        s.requestRenameSelected()
        assertEquals(NodeId("s1-root"), s.renameRequest)
        s.clearRenameRequest()
        assertNull(s.renameRequest)
    }
}

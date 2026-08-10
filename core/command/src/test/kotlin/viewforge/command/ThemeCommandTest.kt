package viewforge.command

import viewforge.command.Fixtures.rootOf
import viewforge.model.ColorPair
import viewforge.model.FrameworkRef
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.model.Theme
import viewforge.model.ThemeCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ThemeCommandTest {
    private val theme = Theme(colors = mapOf("primary" to ColorPair("#6750A4", "#D0BCFF")))

    private fun themed(): Project {
        // A screen whose Text binds its color to colors.primary, so rename propagation is observable.
        val text = Node(
            id = NodeId("a"),
            type = "compose.material3.Text",
            props = mapOf("color" to PropValue.ThemeRef("colors.primary")),
        )
        val root = Node(id = NodeId("root"), type = "compose.foundation.layout.Column", children = listOf(text))
        return Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            theme = theme,
            screens = listOf(Screen(id = "s1", name = "Home", root = root)),
        )
    }

    private fun colorTokenOf(doc: Project): String? =
        (doc.rootOf("s1").children[0].props["color"] as? PropValue.ThemeRef)?.token

    // --- SetTheme ---------------------------------------------------------------------------------

    @Test
    fun `SetTheme replaces the theme and inverts to the prior one`() {
        val doc = themed()
        val next = Theme(colors = mapOf("primary" to ColorPair("#000000", "#FFFFFF")))
        val cmd = SetTheme(next)
        val applied = cmd.apply(doc)
        assertEquals(next, applied.theme)
        val reverted = cmd.invert(doc).apply(applied)
        assertEquals(theme, reverted.theme)
    }

    @Test
    fun `SetTheme apply returns same instance when unchanged`() {
        val doc = themed()
        assertSame(doc, SetTheme(doc.theme).apply(doc))
    }

    @Test
    fun `SetTheme coalesces a run of edits into one history entry`() {
        val history = History()
        val doc0 = themed()
        val key = Triple("colors", "primary", "light")
        val doc1 = history.execute(
            SetTheme(theme.copy(colors = mapOf("primary" to ColorPair("#111111", "#D0BCFF"))), key),
            doc0,
        )
        val doc2 = history.execute(
            SetTheme(theme.copy(colors = mapOf("primary" to ColorPair("#222222", "#D0BCFF"))), key),
            doc1,
        )
        assertEquals("#222222", doc2.theme.colors.getValue("primary").light)
        // One coalesced entry: a single undo returns all the way to the original theme.
        val undone = history.undo(doc2)
        assertEquals(theme, undone.theme)
    }

    // --- RenameThemeToken -------------------------------------------------------------------------

    @Test
    fun `RenameThemeToken renames the key and every reference`() {
        val doc = themed()
        val applied = RenameThemeToken(ThemeCategory.COLORS, "primary", "brand").apply(doc)
        assertEquals(listOf("brand"), applied.theme.colors.keys.toList())
        assertEquals("colors.brand", colorTokenOf(applied))
    }

    @Test
    fun `RenameThemeToken inverts cleanly`() {
        val doc = themed()
        val cmd = RenameThemeToken(ThemeCategory.COLORS, "primary", "brand")
        val applied = cmd.apply(doc)
        val reverted = cmd.invert(doc).apply(applied)
        assertEquals(listOf("primary"), reverted.theme.colors.keys.toList())
        assertEquals("colors.primary", colorTokenOf(reverted))
    }

    @Test
    fun `RenameThemeToken is a no-op for an invalid rename`() {
        val doc = themed()
        val cmd = RenameThemeToken(ThemeCategory.COLORS, "missing", "brand")
        assertSame(doc, cmd.apply(doc))
        assertSame(NoOp, cmd.invert(doc))
    }
}

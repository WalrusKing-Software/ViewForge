package viewforge.editor.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The command palette (S4, #123) is a thin view over the editor's existing actions, so what needs locking
 * down is the *pure* part: [rankCommands] (fuzzy match + ordering). The command wiring itself reuses the
 * menu models, which [AppMenuBarTest] already covers. No composition or framework needed here.
 */
class CommandPaletteTest {
    private fun cmd(title: String, category: String = "Test") =
        PaletteCommand(id = title, title = title, category = category)

    private val commands = listOf(
        cmd("Undo"),
        cmd("Redo"),
        cmd("Copy"),
        cmd("Paste"),
        cmd("Toggle Palette"),
        cmd("Go to screen: Home"),
    )

    @Test
    fun `a blank query keeps the full catalog in order`() {
        assertEquals(commands, rankCommands("   ", commands))
    }

    @Test
    fun `a query that matches nothing yields no results`() {
        assertTrue(rankCommands("zzzz", commands).isEmpty())
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(listOf("Undo"), rankCommands("UND", commands).map { it.title })
    }

    @Test
    fun `an exact or prefix match outranks a mere substring`() {
        val list = listOf(cmd("Toggle Palette"), cmd("Palette"))
        // "pal" is a prefix of "Palette" but only a substring of "Toggle Palette".
        assertEquals(listOf("Palette", "Toggle Palette"), rankCommands("pal", list).map { it.title })
    }

    @Test
    fun `a contiguous substring outranks a scattered subsequence`() {
        val list = listOf(cmd("Copy paste"), cmd("Compact display"))
        // "cop" is contiguous in "Copy paste" but only a subsequence (c…o…p) in "Compact display".
        assertEquals(listOf("Copy paste", "Compact display"), rankCommands("cop", list).map { it.title })
    }

    @Test
    fun `a subsequence match is found even when the letters are not adjacent`() {
        // "gth" appears in order across "Go to screen: Home" but never contiguously.
        assertEquals(listOf("Go to screen: Home"), rankCommands("gth", commands).map { it.title })
    }
}

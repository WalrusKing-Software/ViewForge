package viewforge.command

import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The screen-level commands (D6): rename, add and remove must each apply cleanly and invert back to the
 * exact prior state, so undo/redo round-trips. Guards (last screen, absent target) collapse to a no-op
 * inverse rather than corrupting history.
 */
class ScreenCommandTest {
    private fun screen(id: String, name: String) =
        Screen(id = id, name = name, root = Node(NodeId("$id-root"), "compose.foundation.layout.Column"))

    // A two-screen document so removes and reorders have something to bite on.
    private fun doc() = Fixtures.project().copy(
        screens = listOf(screen("s1", "Home"), screen("s2", "Details")),
    )

    @Test
    fun `RenameScreen changes the name and inverts to the old one`() {
        val before = doc()
        val cmd = RenameScreen("s2", "Settings")
        val after = cmd.apply(before)
        assertEquals("Settings", after.screens.first { it.id == "s2" }.name)
        // Other screens untouched.
        assertEquals("Home", after.screens.first { it.id == "s1" }.name)

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.screens, restored.screens)
    }

    @Test
    fun `RenameScreen leaves the node tree untouched (structure is not affected by names)`() {
        val before = doc()
        val after = RenameScreen("s1", "Landing").apply(before)
        assertEquals(before.screens[0].root, after.screens[0].root)
    }

    @Test
    fun `AddScreen inserts at the index and inverts by removing it`() {
        val before = doc()
        val fresh = screen("s3", "Profile")
        val cmd = AddScreen(fresh, index = 1)
        val after = cmd.apply(before)
        assertEquals(listOf("s1", "s3", "s2"), after.screens.map { it.id })

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.screens, restored.screens)
    }

    @Test
    fun `AddScreen clamps an out-of-range index to an append`() {
        val before = doc()
        val after = AddScreen(screen("s3", "Profile"), index = 99).apply(before)
        assertEquals(listOf("s1", "s2", "s3"), after.screens.map { it.id })
    }

    @Test
    fun `RemoveScreen removes the screen and inverts by restoring it at its old index`() {
        val before = doc()
        val cmd = RemoveScreen("s1") // remove the first, so restoring at index 0 is meaningful
        val after = cmd.apply(before)
        assertEquals(listOf("s2"), after.screens.map { it.id })

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.screens, restored.screens)
    }

    @Test
    fun `RemoveScreen refuses to remove the last screen and inverts to a no-op`() {
        val single = doc().copy(screens = listOf(screen("s1", "Home")))
        val cmd = RemoveScreen("s1")
        assertEquals(single.screens, cmd.apply(single).screens) // unchanged
        assertTrue(cmd.invert(single) is NoOp)
    }

    @Test
    fun `RemoveScreen of an absent id inverts to a no-op`() {
        val before = doc()
        assertTrue(RemoveScreen("nope").invert(before) is NoOp)
    }
}

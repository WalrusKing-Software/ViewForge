package viewforge.editor.panels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure tree keyboard-nav decisions (T5, #101): Up/Down clamp along the navigable order, and Left/Right
 * expand/collapse or step in/out based only on children + expanded state.
 */
class TreeKeyNavTest {
    private val order = listOf("a", "b", "c")

    @Test
    fun `down moves to the next row and up to the previous`() {
        assertEquals("b", nextNavigable(order, "a", +1))
        assertEquals("a", nextNavigable(order, "b", -1))
    }

    @Test
    fun `movement clamps at both ends`() {
        assertEquals("c", nextNavigable(order, "c", +1))
        assertEquals("a", nextNavigable(order, "a", -1))
    }

    @Test
    fun `with no current, down lands on the first and up on the last`() {
        assertEquals("a", nextNavigable(order, null, +1))
        assertEquals("c", nextNavigable(order, null, -1))
    }

    @Test
    fun `empty order has nowhere to go`() {
        assertNull(nextNavigable(emptyList(), "a", +1))
    }

    @Test
    fun `right expands a collapsed container, then steps into its child`() {
        assertEquals(TreeKeyAction.Expand, horizontalTreeAction(hasChildren = true, expanded = false, right = true))
        assertEquals(
            TreeKeyAction.ToFirstChild,
            horizontalTreeAction(hasChildren = true, expanded = true, right = true),
        )
    }

    @Test
    fun `right on a leaf does nothing`() {
        assertEquals(TreeKeyAction.None, horizontalTreeAction(hasChildren = false, expanded = false, right = true))
    }

    @Test
    fun `left collapses an expanded container, else steps out to the parent`() {
        assertEquals(TreeKeyAction.Collapse, horizontalTreeAction(hasChildren = true, expanded = true, right = false))
        assertEquals(TreeKeyAction.ToParent, horizontalTreeAction(hasChildren = true, expanded = false, right = false))
        assertEquals(TreeKeyAction.ToParent, horizontalTreeAction(hasChildren = false, expanded = false, right = false))
    }
}

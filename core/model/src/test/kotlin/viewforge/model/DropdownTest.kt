package viewforge.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `vforge.dropdown` contract helpers (ADR-034 slice 2, #253): the options binding to a list-of-record field
 * and the literal label-field selector. Both are ordinary props, so a dropdown is additive — no schema change.
 */
class DropdownTest {
    @Test
    fun `node carries the options binding and label field`() {
        val node = Dropdown.node("guests", "name")
        assertEquals(Dropdown.TYPE, node.type)
        assertEquals("guests", Dropdown.optionsOf(node))
        assertEquals("name", Dropdown.labelFieldOf(node))
    }

    @Test
    fun `an unbound dropdown has an empty source path and no label field`() {
        val node = Dropdown.node()
        assertEquals("", Dropdown.optionsOf(node))
        assertNull(Dropdown.labelFieldOf(node))
    }

    @Test
    fun `helpers ignore a non-dropdown node`() {
        val text = Node(NodeId("x"), "compose.material3.Text")
        assertNull(Dropdown.optionsOf(text))
        assertNull(Dropdown.labelFieldOf(text))
    }

    @Test
    fun `a blank label field reads as none`() {
        val node = Dropdown.node("guests", "   ")
        assertNull(Dropdown.labelFieldOf(node))
    }
}

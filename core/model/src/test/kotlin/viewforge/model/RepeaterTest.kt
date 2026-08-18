package viewforge.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `vforge.repeat` contract helpers (ADR-034): source binding and the additive slice-2 `layout` mode.
 * Layout is a plain literal prop, so a repeat without it reads as `forEach` — no schema change.
 */
class RepeaterTest {
    @Test
    fun `layoutOf defaults to forEach when the prop is absent`() {
        val node = Repeater.node("users")
        assertEquals(Repeater.LAYOUT_FOR_EACH, Repeater.layoutOf(node))
        assertFalse(Repeater.isLazyColumn(node))
    }

    @Test
    fun `layoutOf reads a lazyColumn literal`() {
        val node = Repeater.node("users").let {
            it.copy(
                props =
                it.props + (Repeater.LAYOUT_PROP to PropValue.Literal(JsonPrimitive(Repeater.LAYOUT_LAZY_COLUMN))),
            )
        }
        assertEquals(Repeater.LAYOUT_LAZY_COLUMN, Repeater.layoutOf(node))
        assertTrue(Repeater.isLazyColumn(node))
    }

    @Test
    fun `layoutOf ignores a non-repeat and a non-literal layout value`() {
        val notRepeat = Node(NodeId("x"), "compose.material3.Text")
        assertEquals(Repeater.LAYOUT_FOR_EACH, Repeater.layoutOf(notRepeat))
        val weird = Repeater.node("users").let {
            it.copy(props = it.props + (Repeater.LAYOUT_PROP to PropValue.StateBinding("nope")))
        }
        assertEquals(Repeater.LAYOUT_FOR_EACH, Repeater.layoutOf(weird))
    }
}

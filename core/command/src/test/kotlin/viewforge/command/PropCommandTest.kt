package viewforge.command

import kotlinx.serialization.json.JsonPrimitive
import viewforge.command.Fixtures.rootOf
import viewforge.model.FrameworkRef
import viewforge.model.ModifierEntry
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.model.findById
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropCommandTest {
    private val doc = Fixtures.project()

    private fun lit(s: String): PropValue = PropValue.Literal(JsonPrimitive(s))

    private fun lit(i: Int): PropValue = PropValue.Literal(JsonPrimitive(i))

    private fun assertRoundTrips(command: Command) {
        val inverse = command.invert(doc)
        val after = command.apply(doc)
        assertEquals(doc, inverse.apply(after), "invert(before).apply(after) must equal before")
    }

    @Test
    fun `SetProp sets a new prop and round-trips`() {
        val cmd = SetProp(Fixtures.SCREEN, NodeId("a"), "text", lit("Hello"))
        val after = cmd.apply(doc)
        assertEquals(lit("Hello"), after.rootOf().findById(NodeId("a"))!!.props["text"])
        assertRoundTrips(cmd)
    }

    @Test
    fun `SetProp with null removes the prop and round-trips`() {
        val withText = SetProp(Fixtures.SCREEN, NodeId("a"), "text", lit("x")).apply(doc)
        val clear = SetProp(Fixtures.SCREEN, NodeId("a"), "text", null)
        val after = clear.apply(withText)
        assertNull(after.rootOf().findById(NodeId("a"))!!.props["text"])
        // Round-trip against the doc that actually has the prop.
        val inverse = clear.invert(withText)
        assertEquals(withText, inverse.apply(after))
    }

    @Test
    fun `SetProp coalesceKey is stable per node and prop`() {
        val a = SetProp(Fixtures.SCREEN, NodeId("a"), "text", lit("1"))
        val b = SetProp(Fixtures.SCREEN, NodeId("a"), "text", lit("2"))
        val other = SetProp(Fixtures.SCREEN, NodeId("a"), "color", lit("#FFF"))
        assertEquals(a.coalesceKey, b.coalesceKey)
        assertTrue(a.coalesceKey != other.coalesceKey)
    }

    @Test
    fun `SetModifiers replaces the ordered chain and round-trips`() {
        val m = ModifierEntry(id = "m1", type = "compose.fillMaxSize")
        val cmd = SetModifiers(Fixtures.SCREEN, NodeId("a"), listOf(m))
        val after = cmd.apply(doc)
        assertEquals(listOf("m1"), after.rootOf().findById(NodeId("a"))!!.modifiers.map { it.id })
        assertRoundTrips(cmd)
    }

    @Test
    fun `SetModifierArg edits one arg of one modifier and round-trips`() {
        // A document whose node "p" carries a padding modifier we can tweak.
        val padded = Node(
            id = NodeId("p"),
            type = "compose.foundation.layout.Box",
            modifiers = listOf(ModifierEntry(id = "mp", type = "compose.padding", args = mapOf("all" to lit(8)))),
        )
        val local = Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(Screen(Fixtures.SCREEN, "Home", padded)),
        )
        val cmd = SetModifierArg(Fixtures.SCREEN, NodeId("p"), "mp", "all", lit(24))
        val after = cmd.apply(local)
        val arg = after.screens.first().root.modifiers.first().args["all"]
        assertEquals(lit(24), arg)
        assertEquals(local, cmd.invert(local).apply(after))
    }
}

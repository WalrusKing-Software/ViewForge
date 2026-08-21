package viewforge.packages.compose.catalog

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.AdvisorySeverity
import viewforge.model.ModifierEntry
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Compose accessibility advisories (#315, I8): missing `contentDescription` on an image/icon, and a
 * tappable control pinned below the 48dp touch target. Non-blocking guidance — these tests pin *which* nodes
 * earn *which* advisory; the inspector rendering is UI, confirmed by running the app.
 */
class ComposeAdvisoriesTest {
    private fun lit(s: String): PropValue = PropValue.Literal(JsonPrimitive(s))

    private fun lit(i: Int): PropValue = PropValue.Literal(JsonPrimitive(i))

    private fun node(
        type: String,
        props: Map<String, PropValue> = emptyMap(),
        modifiers: List<ModifierEntry> = emptyList(),
    ) = Node(NodeId.random(), type, props = props, modifiers = modifiers)

    private fun sizeMod(vararg args: Pair<String, PropValue>) =
        ModifierEntry(id = "m", type = "compose.size", args = args.toMap())

    private fun messages(node: Node): List<String> = ComposeAdvisories.forNode(node).map { it.message }

    // --- contentDescription ----------------------------------------------------------------------

    @Test
    fun `an image with no contentDescription is advised`() {
        val a = ComposeAdvisories.forNode(node("compose.foundation.Image"))
        assertEquals(1, a.size)
        assertEquals(AdvisorySeverity.WARNING, a.single().severity)
        assertTrue("contentDescription" in a.single().message)
    }

    @Test
    fun `a blank contentDescription is still advised, but a real one or a binding is not`() {
        assertEquals(1, messages(node("compose.material3.Icon", mapOf("contentDescription" to lit("")))).size)
        assertTrue(messages(node("compose.material3.Icon", mapOf("contentDescription" to lit("Cart")))).isEmpty())
        // A binding supplies a value at runtime, so it passes (not evaluated here).
        assertTrue(
            messages(node("compose.foundation.Image", mapOf("contentDescription" to PropValue.StateBinding("label"))))
                .isEmpty(),
        )
    }

    @Test
    fun `a component that does not render an image earns no contentDescription advisory`() {
        assertTrue(messages(node("compose.material3.Text")).isEmpty())
    }

    // --- touch target ----------------------------------------------------------------------------

    @Test
    fun `a tappable control pinned below 48dp is advised, naming the offending size`() {
        val a = ComposeAdvisories.forNode(
            node("compose.material3.Button", modifiers = listOf(sizeMod("width" to lit(40), "height" to lit(40)))),
        )
        assertEquals(1, a.size)
        assertTrue("40dp" in a.single().message && "48dp" in a.single().message)
    }

    @Test
    fun `width and height modifiers are checked, and 48dp exactly is fine`() {
        assertEquals(
            1,
            messages(
                node(
                    "compose.material3.Checkbox",
                    modifiers = listOf(ModifierEntry("m", "compose.width", mapOf("value" to lit(24)))),
                ),
            ).size,
        )
        assertTrue(
            messages(
                node(
                    "compose.material3.Switch",
                    modifiers = listOf(ModifierEntry("m", "compose.height", mapOf("value" to lit(48)))),
                ),
            ).isEmpty(),
        )
        assertTrue(
            messages(
                node("compose.material3.Button", modifiers = listOf(sizeMod("width" to lit(64), "height" to lit(64)))),
            ).isEmpty(),
        )
    }

    @Test
    fun `a non-tappable node below 48dp is not a touch-target concern`() {
        assertTrue(
            messages(
                node("compose.material3.Text", modifiers = listOf(sizeMod("width" to lit(20), "height" to lit(20)))),
            ).isEmpty(),
        )
    }

    @Test
    fun `an expression-valued size is not evaluated, so it raises no touch-target advisory`() {
        assertTrue(
            messages(
                node(
                    "compose.material3.Button",
                    modifiers = listOf(
                        ModifierEntry("m", "compose.width", mapOf("value" to PropValue.RawExpression("x"))),
                    ),
                ),
            ).isEmpty(),
        )
    }
}

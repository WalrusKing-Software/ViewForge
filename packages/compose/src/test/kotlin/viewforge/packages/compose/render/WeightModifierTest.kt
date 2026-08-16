package viewforge.packages.compose.render

import androidx.compose.ui.Modifier
import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ModifierEntry
import viewforge.model.PropValue
import viewforge.model.Theme
import viewforge.packages.compose.catalog.ComposeModifiers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The `weight` modifier is a RowScope/ColumnScope extension, so the renderer applies it only through the
 * scope carried in [RenderContext.weightApplier] (#158). These lock down the render half of the fix — that
 * weight is applied for a Row/Column child, no-ops elsewhere, and no-ops for a non-positive value — plus
 * the catalog gating that keeps the inspector from offering it out of scope. Codegen's mirror is proven by
 * the `Weight` golden + the compile gate.
 */
class WeightModifierTest {
    private fun ctx(applier: ((Modifier, Float) -> Modifier)?): RenderContext =
        RenderContext(theme = Theme(), dark = false, weightApplier = applier)

    private fun weight(value: Int): ModifierEntry = ModifierEntry(
        id = "w",
        type = "compose.weight",
        args = mapOf(
            "weight" to PropValue.Literal(JsonPrimitive(value)),
        ),
    )

    @Test
    fun `weight applies through the scope with its value`() {
        var captured: Float? = null
        buildModifier(
            listOf(weight(2)),
            ctx { base, w ->
                captured = w
                base
            },
        )
        assertEquals(2f, captured, "a Row/Column child's weight should reach the scope applier")
    }

    @Test
    fun `weight is a no-op with no scope (parent is not a Row or Column)`() {
        // A null applier models any non-Row/Column parent; weight must fold away rather than crash.
        val result = buildModifier(listOf(weight(2)), ctx(applier = null))
        assertSame(Modifier, result, "weight outside a Row/Column scope should contribute nothing")
    }

    @Test
    fun `a non-positive weight never reaches the applier`() {
        var called = false
        buildModifier(
            listOf(weight(0)),
            ctx { base, _ ->
                called = true
                base
            },
        )
        assertFalse(called, "Compose rejects weight <= 0, so it must be treated as a no-op")
    }

    @Test
    fun `the catalog offers weight only inside a Row or Column`() {
        val types = { parent: String? -> ComposeModifiers.offeredFor(parent).map { it.type } }
        assertTrue("compose.weight" in types("compose.foundation.layout.Row"))
        assertTrue("compose.weight" in types("compose.foundation.layout.Column"))
        assertFalse("compose.weight" in types("compose.foundation.layout.Box"))
        assertFalse("compose.weight" in types(null), "the root has no Row/Column parent")
        // Gating weight must not drop the other, scope-free modifiers.
        assertTrue("compose.padding" in types("compose.foundation.layout.Box"))
    }
}

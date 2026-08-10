package viewforge.editor.panels

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.PropValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropEditingTest {
    @Test
    fun `literalText and literalBool read scalars`() {
        assertEquals("hi", PropValue.Literal(JsonPrimitive("hi")).literalText())
        assertEquals(true, PropValue.Literal(JsonPrimitive(true)).literalBool())
        assertEquals(false, PropValue.Literal(JsonPrimitive(false)).literalBool())
        assertNull(PropValue.ThemeRef("colors.primary").literalText())
        assertEquals("colors.primary", PropValue.ThemeRef("colors.primary").themeToken())
        assertEquals("{ x }", PropValue.RawExpression("{ x }").expressionCode())
    }

    @Test
    fun `parseIntInput handles blank, invalid, range, and valid`() {
        assertTrue(parseIntInput("") is NumberResult.Cleared)
        assertTrue(parseIntInput("abc") is NumberResult.Invalid)
        assertEquals(NumberResult.Valid(intValue(12)), parseIntInput("12"))
        // Out of range is invalid.
        assertTrue(parseIntInput("200", 0f..100f) is NumberResult.Invalid)
        assertEquals(NumberResult.Valid(intValue(50)), parseIntInput("50", 0f..100f))
    }

    @Test
    fun `parseFloatInput respects range`() {
        assertEquals(NumberResult.Valid(floatValue(0.5f)), parseFloatInput("0.5", 0f..1f))
        assertTrue(parseFloatInput("2.0", 0f..1f) is NumberResult.Invalid)
        assertTrue(parseFloatInput("x") is NumberResult.Invalid)
    }

    @Test
    fun `normalizeHex accepts 3-6-8 digit forms and rejects junk`() {
        assertEquals("#FFAABB", normalizeHex("faB"))
        assertEquals("#6750A4", normalizeHex("#6750a4"))
        assertEquals("#FF6750A4", normalizeHex("ff6750a4"))
        assertNull(normalizeHex("#12"))
        assertNull(normalizeHex("nothex"))
        assertFalse(isValidHex("#12"))
        assertTrue(isValidHex("#6750A4"))
    }

    @Test
    fun `hexToArgb packs opaque and alpha forms`() {
        assertEquals(0xFF6750A4L, hexToArgb("#6750A4"))
        assertEquals(0x806750A4L, hexToArgb("#806750A4"))
        assertNull(hexToArgb("zzz")) // not hex digits
    }
}

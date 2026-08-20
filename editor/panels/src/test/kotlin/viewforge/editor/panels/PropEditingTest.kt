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

    @Test
    fun `argbToHex drops alpha when opaque and keeps it otherwise`() {
        assertEquals("#6750A4", argbToHex(a = 255, r = 0x67, g = 0x50, b = 0xA4))
        assertEquals("#806750A4", argbToHex(a = 0x80, r = 0x67, g = 0x50, b = 0xA4))
        // Pads single-digit components and upper-cases.
        assertEquals("#0A0B0C", argbToHex(a = 255, r = 10, g = 11, b = 12))
    }

    @Test
    fun `argbToHex clamps out-of-range components`() {
        assertEquals("#FFFFFF", argbToHex(a = 300, r = 999, g = 256, b = 255))
        assertEquals("#000000", argbToHex(a = 255, r = -5, g = -1, b = 0))
    }

    @Test
    fun `argbToHex round-trips through hexToArgb`() {
        for (hex in listOf("#6750A4", "#806750A4", "#000000", "#FFFFFF", "#00FF8040")) {
            val packed = hexToArgb(hex)!!
            val a = ((packed shr 24) and 0xFFL).toInt()
            val r = ((packed shr 16) and 0xFFL).toInt()
            val g = ((packed shr 8) and 0xFFL).toInt()
            val b = (packed and 0xFFL).toInt()
            // The picker's components re-serialize to a hex that packs back to the same ARGB.
            assertEquals(packed, hexToArgb(argbToHex(a, r, g, b)))
        }
    }
}

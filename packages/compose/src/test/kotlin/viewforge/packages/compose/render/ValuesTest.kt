package viewforge.packages.compose.render

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ColorPair
import viewforge.model.PropValue
import viewforge.model.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the interpreter's pure value layer (ARCHITECTURE §4). These cover the parsing that
 * would otherwise only be exercised through a full Compose UI test — colors, modifier args, and
 * theme-token resolution — so regressions surface fast and without a UI harness.
 */
class ValuesTest {
    private fun lit(value: Int) = PropValue.Literal(JsonPrimitive(value))

    private fun lit(value: String) = PropValue.Literal(JsonPrimitive(value))

    // --- colors ---

    @Test
    fun `parses 6-digit hex as opaque ARGB`() {
        assertEquals(0xFF6750A4L, parseColorArgb("#6750A4"))
        assertEquals(0xFF6750A4L, parseColorArgb("6750A4"), "leading # is optional")
    }

    @Test
    fun `parses 3-digit and 8-digit hex`() {
        assertEquals(0xFFFF0000L, parseColorArgb("#F00"))
        assertEquals(0x80FF0000L, parseColorArgb("#80FF0000"))
    }

    @Test
    fun `rejects malformed hex`() {
        assertNull(parseColorArgb("#12"))
        assertNull(parseColorArgb("#GGGGGG"))
        assertNull(parseColorArgb(null))
    }

    // --- theme token resolution ---

    private val theme = Theme(colors = mapOf("primary" to ColorPair(light = "#6750A4", dark = "#D0BCFF")))

    @Test
    fun `resolves a color token to the light or dark half`() {
        assertEquals("#6750A4", resolveColorHex(theme, "colors.primary", dark = false))
        assertEquals("#D0BCFF", resolveColorHex(theme, "colors.primary", dark = true))
    }

    @Test
    fun `returns null for unknown or non-color tokens`() {
        assertNull(resolveColorHex(theme, "colors.missing", dark = false))
        assertNull(resolveColorHex(theme, "typography.titleLarge", dark = false))
    }

    @Test
    fun `colorArgb resolves both literals and theme refs`() {
        assertEquals(0xFF6750A4L, colorArgb(lit("#6750A4"), theme, dark = false))
        assertEquals(0xFFD0BCFFL, colorArgb(PropValue.ThemeRef("colors.primary"), theme, dark = true))
        assertNull(colorArgb(PropValue.RawExpression("Color.Red"), theme, dark = false))
    }

    @Test
    fun `extracts typography token name only for typography refs`() {
        assertEquals("titleLarge", typographyTokenName("typography.titleLarge"))
        assertNull(typographyTokenName("colors.primary"))
    }

    // --- modifier arg specs ---

    @Test
    fun `padding all fills every edge and specific edges override`() {
        assertEquals(PaddingSpec(24, 24, 24, 24), paddingSpec(mapOf("all" to lit(24))))
        assertEquals(
            PaddingSpec(start = 8, top = 16, end = 8, bottom = 16),
            paddingSpec(mapOf("horizontal" to lit(8), "vertical" to lit(16))),
        )
        assertEquals(
            PaddingSpec(start = 24, top = 4, end = 24, bottom = 24),
            paddingSpec(mapOf("all" to lit(24), "top" to lit(4))),
        )
    }

    @Test
    fun `empty padding args are all zero`() {
        assertEquals(PaddingSpec(0, 0, 0, 0), paddingSpec(emptyMap()))
    }

    @Test
    fun `size all sets both axes while width and height set one each`() {
        assertEquals(SizeSpec(width = 48, height = 48), sizeSpec(mapOf("all" to lit(48))))
        assertEquals(SizeSpec(width = 100, height = null), sizeSpec(mapOf("width" to lit(100))))
    }

    @Test
    fun `single dimension reads value or the axis key`() {
        assertEquals(120, singleDimen(mapOf("value" to lit(120)), "width"))
        assertEquals(64, singleDimen(mapOf("height" to lit(64)), "height"))
        assertNull(singleDimen(emptyMap(), "width"))
    }

    // --- alignment / arrangement mapping ---

    @Test
    fun `alignment names map with a sane default for the unknown`() {
        assertEquals(HAlign.CenterHorizontally, hAlign("CenterHorizontally"))
        assertEquals(HAlign.Start, hAlign(null))
        assertEquals(HAlign.Start, hAlign("Bogus"))
        assertEquals(VArrange.Center, vArrange("Center"))
        assertEquals(VArrange.SpaceBetween, vArrange("SpaceBetween"))
        assertEquals(HArrange.Start, hArrange(null))
    }
}

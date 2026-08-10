package viewforge.editor.panels

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.PropValue

/**
 * Pure, Compose-free logic behind the typed inspector controls (M5): reading a [PropValue] into the
 * text/number/flag a control shows, building a [PropValue] back from user input, and validating it
 * before it is committed (I8). Kept here so the tricky parts are unit-tested without a UI harness; the
 * `@Composable` controls in `PropControls.kt` stay thin over these.
 *
 * Nothing here evaluates a [PropValue.RawExpression] — it is only ever displayed and passed through
 * verbatim (CLAUDE.md rule 8, PF-4).
 */

// --- reading current values ----------------------------------------------------------------------

/** The literal scalar's text, or null if the value is absent or not a literal. */
internal fun PropValue?.literalText(): String? = (this as? PropValue.Literal)?.value?.content

/** The literal read as a bool (accepts `true`/`false`), or null. */
internal fun PropValue?.literalBool(): Boolean? = when (this.literalText()?.lowercase()) {
    "true" -> true
    "false" -> false
    else -> null
}

/** The theme token a value binds to (e.g. "colors.primary"), or null if it is not a [PropValue.ThemeRef]. */
internal fun PropValue?.themeToken(): String? = (this as? PropValue.ThemeRef)?.token

/** The raw expression code, or null if the value is not a [PropValue.RawExpression]. */
internal fun PropValue?.expressionCode(): String? = (this as? PropValue.RawExpression)?.code

// --- building values from input ------------------------------------------------------------------

internal fun stringValue(text: String): PropValue = PropValue.Literal(JsonPrimitive(text))

internal fun boolValue(on: Boolean): PropValue = PropValue.Literal(JsonPrimitive(on))

internal fun intValue(n: Int): PropValue = PropValue.Literal(JsonPrimitive(n))

internal fun floatValue(f: Float): PropValue = PropValue.Literal(JsonPrimitive(f))

internal fun themeValue(token: String): PropValue = PropValue.ThemeRef(token)

internal fun expressionValue(code: String): PropValue = PropValue.RawExpression(code)

// --- numeric parsing/validation ------------------------------------------------------------------

/**
 * Parse [text] as an integer within an optional [range]. Blank → null (meaning "clear the prop"). A
 * non-integer or out-of-range value → [NumberResult.Invalid] so the control can show an error and
 * withhold the commit (I8).
 */
internal fun parseIntInput(text: String, range: ClosedFloatingPointRange<Float>? = null): NumberResult {
    if (text.isBlank()) return NumberResult.Cleared
    val n = text.trim().toIntOrNull() ?: return NumberResult.Invalid
    if (range != null && (n < range.start || n > range.endInclusive)) return NumberResult.Invalid
    return NumberResult.Valid(intValue(n))
}

/** Parse [text] as a float within an optional [range]. Same contract as [parseIntInput]. */
internal fun parseFloatInput(text: String, range: ClosedFloatingPointRange<Float>? = null): NumberResult {
    if (text.isBlank()) return NumberResult.Cleared
    val f = text.trim().toFloatOrNull() ?: return NumberResult.Invalid
    if (range != null && (f < range.start || f > range.endInclusive)) return NumberResult.Invalid
    return NumberResult.Valid(floatValue(f))
}

/** Outcome of parsing numeric input: a committable value, an explicit clear, or an invalid entry. */
internal sealed interface NumberResult {
    data class Valid(val value: PropValue) : NumberResult

    data object Cleared : NumberResult

    data object Invalid : NumberResult
}

// --- colors --------------------------------------------------------------------------------------

/**
 * Normalize a hex color string to `#RRGGBB`/`#AARRGGBB` (upper-case, leading `#`), or null if it is
 * not a valid `#RGB`/`#RRGGBB`/`#AARRGGBB`. Mirrors what the renderer's `parseColorArgb` accepts, so a
 * value the inspector calls valid is one the canvas can draw.
 */
internal fun normalizeHex(text: String): String? {
    val h = text.trim().removePrefix("#").uppercase()
    val expanded = when (h.length) {
        3 -> h.map { "$it$it" }.joinToString("")
        6, 8 -> h
        else -> return null
    }
    if (!expanded.all { it in '0'..'9' || it in 'A'..'F' }) return null
    return "#$expanded"
}

/** True if [text] is a valid hex color (used for inline validation, I8). */
internal fun isValidHex(text: String): Boolean = normalizeHex(text) != null

/** Packs a normalized hex into `0xAARRGGBB` for a swatch fill, or null if invalid. Missing alpha is opaque. */
internal fun hexToArgb(text: String): Long? {
    val h = normalizeHex(text)?.removePrefix("#") ?: return null
    val full = if (h.length == 6) "FF$h" else h
    return full.toLongOrNull(16)
}

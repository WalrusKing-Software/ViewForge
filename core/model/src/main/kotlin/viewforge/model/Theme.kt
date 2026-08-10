package viewforge.model

import kotlinx.serialization.Serializable

/**
 * A light/dark color pair, hex strings (DATA_MODEL §8). Light/dark from the start — retrofitting
 * dark mode into a single-value schema would be a migration.
 */
@Serializable
data class ColorPair(val light: String, val dark: String)

/** A typography token (DATA_MODEL §8). */
@Serializable
data class TypographyToken(
    val fontFamily: String = "default",
    val fontSize: Int,
    val fontWeight: Int = 400,
    val lineHeight: Int,
)

/**
 * The project theme (DATA_MODEL §8). Tokens are referenced by [PropValue.ThemeRef], so a rename can
 * propagate automatically. All maps default empty so a minimal theme serializes to almost nothing.
 */
@Serializable
data class Theme(
    val colors: Map<String, ColorPair> = emptyMap(),
    val typography: Map<String, TypographyToken> = emptyMap(),
    val shapes: Map<String, Int> = emptyMap(),
    val spacing: Map<String, Int> = emptyMap(),
)

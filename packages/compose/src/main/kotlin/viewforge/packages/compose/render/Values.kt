package viewforge.packages.compose.render

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import viewforge.model.PropValue
import viewforge.model.Theme

/**
 * The framework-agnostic *value* layer of the interpreter (ARCHITECTURE §4). Everything here is
 * pure and Compose-free on purpose: it parses IR prop/modifier values into plain data so it can be
 * unit-tested without a Compose UI harness. The thin `@Composable` layer (Components/Modifiers)
 * turns these into real widgets.
 */

// --- Reading typed prop values (DATA_MODEL §6) ---------------------------------------------------

/** The underlying JSON scalar of a [PropValue.Literal], or null for any other kind. */
internal fun PropValue?.asPrimitive(): JsonPrimitive? = (this as? PropValue.Literal)?.value

/** A literal read as text — used for enum-like props ("CenterHorizontally") and [Text] content. */
internal fun PropValue?.literalString(): String? = asPrimitive()?.content

internal fun PropValue?.literalInt(): Int? = asPrimitive()?.intOrNull

internal fun PropValue?.literalFloat(): Float? = asPrimitive()?.floatOrNull

internal fun PropValue?.literalBoolean(): Boolean? = asPrimitive()?.booleanOrNull

// --- Theme slots ---------------------------------------------------------------------------------

/**
 * The Material `colorScheme` slots a project theme may override by naming a `colors.<slot>` token —
 * the canonical set shared by the canvas ([projectColorScheme]) and codegen (`CodegenValues`), so the
 * two can never disagree about which tokens map onto the scheme (the ADR-018 render/codegen marriage).
 * Ordered so generated `lightColorScheme(...)` args are deterministic for the golden tests.
 */
internal val MATERIAL_COLOR_SLOTS: List<String> = listOf(
    "primary", "onPrimary", "secondary", "onSecondary",
    "background", "onBackground", "surface", "onSurface", "error", "onError",
)

/** The Material `shapes` slots a project theme may override by naming a `shapes.<slot>` token. */
internal val MATERIAL_SHAPE_SLOTS: List<String> = listOf("small", "medium", "large")

// --- Colors --------------------------------------------------------------------------------------

/**
 * Parses a hex color string into a packed `0xAARRGGBB` value, or null if it isn't a valid color.
 * Accepts `#RGB`, `#RRGGBB`, and `#AARRGGBB` (the leading `#` is optional). Missing alpha is opaque.
 * Returns a value the Compose layer wraps in `Color(Long)`; kept pure so it is directly testable.
 */
internal fun parseColorArgb(hex: String?): Long? {
    if (hex == null) return null
    val h = hex.trim().removePrefix("#")
    val full =
        when (h.length) {
            3 -> "FF" + h.map { "$it$it" }.joinToString("")
            6 -> "FF$h"
            8 -> h
            else -> return null
        }
    return full.toLongOrNull(16)
}

/**
 * Resolves a [PropValue] to a packed color, following the same precedence the render layer uses for
 * project-theme tokens. Returns null when the value is a theme token the project doesn't define — the
 * `@Composable` layer then falls back to the Material scheme, which needs composition and so can't
 * live here.
 */
internal fun colorArgb(value: PropValue?, theme: Theme, dark: Boolean): Long? = when (value) {
    is PropValue.Literal -> parseColorArgb(value.value.content)
    is PropValue.ThemeRef -> parseColorArgb(resolveColorHex(theme, value.token, dark))
    else -> null
}

/** Resolves `"colors.<name>"` against the project theme's light/dark pair, or null if absent. */
internal fun resolveColorHex(theme: Theme, token: String, dark: Boolean): String? {
    val name = token.removePrefix("colors.")
    if (name == token) return null // not a color token
    val pair = theme.colors[name] ?: return null
    return if (dark) pair.dark else pair.light
}

/** Extracts `"typography.<name>"` → `<name>`, or null if the token isn't a typography reference. */
internal fun typographyTokenName(token: String): String? = token.removePrefix("typography.").takeIf { it != token }

// --- Alignment / arrangement (kept as small enums so the mapping is testable) --------------------

internal enum class HAlign { Start, CenterHorizontally, End }

internal enum class VAlign { Top, CenterVertically, Bottom }

internal enum class VArrange { Top, Center, Bottom, SpaceBetween, SpaceAround, SpaceEvenly }

internal enum class HArrange { Start, Center, End, SpaceBetween, SpaceAround, SpaceEvenly }

internal enum class BoxAlign {
    TopStart,
    TopCenter,
    TopEnd,
    CenterStart,
    Center,
    CenterEnd,
    BottomStart,
    BottomCenter,
    BottomEnd,
}

internal fun hAlign(name: String?): HAlign = when (name) {
    "Start" -> HAlign.Start
    "End" -> HAlign.End
    else -> HAlign.CenterHorizontally.takeIf { name == "CenterHorizontally" } ?: HAlign.Start
}

internal fun vAlign(name: String?): VAlign = when (name) {
    "CenterVertically" -> VAlign.CenterVertically
    "Bottom" -> VAlign.Bottom
    else -> VAlign.Top
}

internal fun vArrange(name: String?): VArrange = when (name) {
    "Center" -> VArrange.Center
    "Bottom" -> VArrange.Bottom
    "SpaceBetween" -> VArrange.SpaceBetween
    "SpaceAround" -> VArrange.SpaceAround
    "SpaceEvenly" -> VArrange.SpaceEvenly
    else -> VArrange.Top
}

internal fun hArrange(name: String?): HArrange = when (name) {
    "Center" -> HArrange.Center
    "End" -> HArrange.End
    "SpaceBetween" -> HArrange.SpaceBetween
    "SpaceAround" -> HArrange.SpaceAround
    "SpaceEvenly" -> HArrange.SpaceEvenly
    else -> HArrange.Start
}

/**
 * The `contentScale` values an `Image` may take (FEATURES §2 content set). A small enum so the
 * mapping to `ContentScale` is testable and shared by the renderer and codegen — the member name is
 * the Compose `ContentScale` member name, so both paths agree without a second lookup table.
 */
internal enum class ImageScale { Fit, Crop, FillBounds, Inside, None }

internal fun imageScale(name: String?): ImageScale = when (name) {
    "Crop" -> ImageScale.Crop
    "FillBounds" -> ImageScale.FillBounds
    "Inside" -> ImageScale.Inside
    "None" -> ImageScale.None
    else -> ImageScale.Fit
}

/**
 * The curated `Icon` allowlist — a small set of common Material icons, all present in
 * `material-icons-core` (already on the classpath via material3, so no extra dependency). This is the
 * single source of truth: the catalog advertises it, the renderer maps each to `Icons.Filled.<name>`,
 * and codegen emits the matching member (kept honest by `CatalogConsistencyTest`). Names are the exact
 * `Icons.Filled` property names.
 */
internal val ICON_NAMES: List<String> = listOf(
    "Home", "Settings", "Search", "Menu", "Close", "Check", "Add", "Delete", "Edit", "Favorite",
    "Star", "Info", "Warning", "ArrowBack", "ArrowForward", "Person", "Share", "ShoppingCart",
    "Refresh", "MoreVert",
)

/** Normalizes an icon prop to a curated name, falling back to `Star` for anything unknown. */
internal fun iconName(name: String?): String = if (name in ICON_NAMES) name!! else "Star"

internal fun boxAlign(name: String?): BoxAlign = when (name) {
    "TopStart" -> BoxAlign.TopStart
    "TopCenter" -> BoxAlign.TopCenter
    "TopEnd" -> BoxAlign.TopEnd
    "CenterStart" -> BoxAlign.CenterStart
    "Center" -> BoxAlign.Center
    "CenterEnd" -> BoxAlign.CenterEnd
    "BottomStart" -> BoxAlign.BottomStart
    "BottomCenter" -> BoxAlign.BottomCenter
    "BottomEnd" -> BoxAlign.BottomEnd
    else -> BoxAlign.TopStart
}

// --- Modifier argument specs (padding/size), parsed once here and applied in the Compose layer ----

/** Resolved edge insets in dp; the render layer maps this to `PaddingValues`. */
internal data class PaddingSpec(val start: Int, val top: Int, val end: Int, val bottom: Int)

/**
 * Reads a `padding` modifier's args (DATA_MODEL §7). Precedence, low → high: `all`, then the
 * `horizontal`/`vertical` axes, then per-edge `start`/`top`/`end`/`bottom`. Anything unspecified is 0.
 */
internal fun paddingSpec(args: Map<String, PropValue>): PaddingSpec {
    val all = args["all"].literalInt()
    val horizontal = args["horizontal"].literalInt() ?: all
    val vertical = args["vertical"].literalInt() ?: all
    return PaddingSpec(
        start = args["start"].literalInt() ?: horizontal ?: 0,
        top = args["top"].literalInt() ?: vertical ?: 0,
        end = args["end"].literalInt() ?: horizontal ?: 0,
        bottom = args["bottom"].literalInt() ?: vertical ?: 0,
    )
}

/** Resolved width/height in dp; null means "unconstrained on that axis". */
internal data class SizeSpec(val width: Int?, val height: Int?)

/** Reads a `size` modifier's args: `all` sets both axes; `width`/`height` set one each. */
internal fun sizeSpec(args: Map<String, PropValue>): SizeSpec {
    val all = args["all"].literalInt()
    return SizeSpec(
        width = args["width"].literalInt() ?: all,
        height = args["height"].literalInt() ?: all,
    )
}

/** Reads a single-dimension modifier (`width`/`height`) whose value is under `value` or the axis key. */
internal fun singleDimen(args: Map<String, PropValue>, key: String): Int? =
    args["value"].literalInt() ?: args[key].literalInt()

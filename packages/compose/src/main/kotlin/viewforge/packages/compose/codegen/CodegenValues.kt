package viewforge.packages.compose.codegen

import com.squareup.kotlinpoet.CodeBlock
import viewforge.model.PropValue
import viewforge.model.Theme
import viewforge.packages.compose.render.boxAlign
import viewforge.packages.compose.render.hAlign
import viewforge.packages.compose.render.hArrange
import viewforge.packages.compose.render.parseColorArgb
import viewforge.packages.compose.render.vAlign
import viewforge.packages.compose.render.vArrange

/**
 * Turns typed IR values into KotlinPoet [CodeBlock]s. This is codegen's half of the render/codegen
 * marriage (TECHNICAL_NOTES §2): it reuses the interpreter's *same* pure value layer
 * (`render/Values.kt` — `parseColorArgb`, the alignment/arrangement parsers) so the canvas and the
 * emitted code can never disagree about what a value means. Everything is a structural `CodeBlock`,
 * never spliced text (GC-1/GC-2).
 *
 * Unrepresentable values fail loudly with [CodegenException] rather than emitting code that won't
 * compile (ARCHITECTURE §7 "validate": codegen is never the first place an error surfaces silently).
 */
internal object CodegenValues {
    /** Material `colorScheme` slots codegen may reference as `MaterialTheme.colorScheme.<slot>`. */
    private val MATERIAL_COLOR_SLOTS = setOf(
        "primary", "onPrimary", "secondary", "onSecondary",
        "background", "onBackground", "surface", "onSurface", "error", "onError",
    )

    /** Material `typography` slots codegen may reference as `MaterialTheme.typography.<slot>`. */
    private val MATERIAL_TYPO_SLOTS = setOf(
        "displayLarge", "displayMedium", "displaySmall",
        "headlineLarge", "headlineMedium", "headlineSmall",
        "titleLarge", "titleMedium", "titleSmall",
        "bodyLarge", "bodyMedium", "bodySmall",
        "labelLarge", "labelMedium", "labelSmall",
    )

    /** A dp literal: `16.dp`. */
    fun dp(value: Int): CodeBlock = CodeBlock.of("%L.%M", value, ComposeNames.dp)

    /** A `Text`'s content: a string literal (escaped by KotlinPoet, GC-2) or a raw expression (GC-4). */
    fun text(value: PropValue?): CodeBlock = when (value) {
        null -> CodeBlock.of("%S", "")
        is PropValue.Literal -> CodeBlock.of("%S", value.value.content)
        is PropValue.RawExpression -> raw(value)
        else -> throw CodegenException("Text 'text' must be a string literal or expression, got $value")
    }

    /** A lambda-valued prop such as `onClick` — only an expression is meaningful; absent → no-op `{}`. */
    fun lambda(value: PropValue?): CodeBlock = when (value) {
        null -> CodeBlock.of("{}")
        is PropValue.RawExpression -> raw(value)
        else -> throw CodegenException("Expected a lambda expression, got $value")
    }

    /** A color-typed value: `Color(0x..)` literal, a Material scheme slot, or a themed literal. */
    fun color(value: PropValue?, theme: Theme): CodeBlock = when (value) {
        is PropValue.Literal -> colorLiteral(value.value.content)
        is PropValue.ThemeRef -> colorTheme(value.token, theme)
        is PropValue.RawExpression -> raw(value)
        else -> throw CodegenException("Color prop must be a literal, theme ref, or expression, got $value")
    }

    /** A typography-typed value: a Material `MaterialTheme.typography.<slot>` or a raw expression. */
    fun typography(value: PropValue?): CodeBlock = when (value) {
        is PropValue.ThemeRef -> {
            val slot = value.token.removePrefix("typography.")
            if (slot == value.token || slot !in MATERIAL_TYPO_SLOTS) {
                // Custom (project-defined) typography tokens emit inline TextStyle — deferred to M8.
                throw CodegenException("Unsupported typography token '${value.token}' (only Material slots in Phase 1)")
            }
            CodeBlock.of("%T.typography.%L", ComposeNames.MaterialTheme, slot)
        }
        is PropValue.RawExpression -> raw(value)
        else -> throw CodegenException("Typography prop must be a theme ref or expression, got $value")
    }

    /**
     * An alignment/arrangement enum prop → `Alignment.<name>` / `Arrangement.<name>`. Normalizes
     * through the *same* parser the renderer uses, so an out-of-range value resolves identically in
     * both paths (no divergence). Shared with [CatalogConsistencyTest] to prove no enum drift.
     */
    fun enum(propName: String, value: PropValue?): CodeBlock = when (value) {
        is PropValue.Literal -> enumMember(propName, value.value.content)
        is PropValue.RawExpression -> raw(value)
        null -> throw CodegenException("Enum prop '$propName' has no value")
        else -> throw CodegenException("Enum prop '$propName' must be a literal or expression, got $value")
    }

    private fun enumMember(propName: String, name: String): CodeBlock = when (propName) {
        "horizontalAlignment" -> CodeBlock.of("%T.%L", ComposeNames.Alignment, hAlign(name).name)
        "verticalAlignment" -> CodeBlock.of("%T.%L", ComposeNames.Alignment, vAlign(name).name)
        "contentAlignment" -> CodeBlock.of("%T.%L", ComposeNames.Alignment, boxAlign(name).name)
        "verticalArrangement" -> CodeBlock.of("%T.%L", ComposeNames.Arrangement, vArrange(name).name)
        "horizontalArrangement" -> CodeBlock.of("%T.%L", ComposeNames.Arrangement, hArrange(name).name)
        else -> throw CodegenException("Unknown enum prop '$propName'")
    }

    private fun colorLiteral(hex: String): CodeBlock {
        val argb = parseColorArgb(hex) ?: throw CodegenException("Invalid color literal '$hex'")
        return CodeBlock.of("%T(0x%L)", ComposeNames.Color, "%08X".format(argb))
    }

    private fun colorTheme(token: String, theme: Theme): CodeBlock {
        val slot = token.removePrefix("colors.")
        if (slot == token) throw CodegenException("Color prop expects a 'colors.*' token, got '$token'")
        if (slot in MATERIAL_COLOR_SLOTS) {
            return CodeBlock.of("%T.colorScheme.%L", ComposeNames.MaterialTheme, slot)
        }
        // A project-defined (non-Material) color: emit its resolved literal so output still compiles.
        val hex = theme.colors[slot]?.light
            ?: throw CodegenException("Unresolved color token '$token' (not a Material slot nor in the theme)")
        return colorLiteral(hex)
    }

    /** Verbatim escape hatch (GC-4): the user is the author; emitted as-is, never validated. */
    private fun raw(value: PropValue.RawExpression): CodeBlock = CodeBlock.of("%L", value.code)
}

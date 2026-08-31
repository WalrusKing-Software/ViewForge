package viewforge.packages.compose.codegen

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.joinToCode
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import viewforge.model.Asset
import viewforge.model.PropValue
import viewforge.model.Theme
import viewforge.model.TypographyToken
import viewforge.packages.compose.render.MATERIAL_COLOR_SLOTS
import viewforge.packages.compose.render.MATERIAL_SHAPE_SLOTS
import viewforge.packages.compose.render.boxAlign
import viewforge.packages.compose.render.fontStyleName
import viewforge.packages.compose.render.fontWeightName
import viewforge.packages.compose.render.hAlign
import viewforge.packages.compose.render.hArrange
import viewforge.packages.compose.render.iconName
import viewforge.packages.compose.render.imageScale
import viewforge.packages.compose.render.parseColorArgb
import viewforge.packages.compose.render.textAlignName
import viewforge.packages.compose.render.textDecorationName
import viewforge.packages.compose.render.textOverflowName
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
    /** Material `typography` slots codegen may reference as `MaterialTheme.typography.<slot>`. */
    val MATERIAL_TYPO_SLOTS = setOf(
        "displayLarge", "displayMedium", "displaySmall",
        "headlineLarge", "headlineMedium", "headlineSmall",
        "titleLarge", "titleMedium", "titleSmall",
        "bodyLarge", "bodyMedium", "bodySmall",
        "labelLarge", "labelMedium", "labelSmall",
    )

    /** A dp literal: `16.dp`. */
    fun dp(value: Int): CodeBlock = CodeBlock.of("%L.%M", value, ComposeNames.dp)

    /** A dp-typed *prop* value → `N.dp` (or a raw expression). Reuses [dp] so both agree on the form. */
    fun dpProp(value: PropValue?): CodeBlock = when (value) {
        is PropValue.Literal ->
            dp(
                value.value.intOrNull
                    ?: throw CodegenException("dp prop expects an integer, got '${value.value.content}'"),
            )
        is PropValue.RawExpression -> raw(value)
        is PropValue.ParamRef -> param(value)
        is PropValue.StateBinding -> binding(value)
        null -> throw CodegenException("dp prop has no value")
        else -> throw CodegenException("dp prop must be a literal or expression, got $value")
    }

    /** A float-typed prop → a Kotlin `Float` literal such as `0.5f` (or a raw expression, GC-4). */
    fun float(value: PropValue?): CodeBlock = when (value) {
        is PropValue.Literal ->
            CodeBlock.of(
                "%Lf",
                value.value.floatOrNull
                    ?: throw CodegenException("float prop expects a number, got '${value.value.content}'"),
            )
        is PropValue.RawExpression -> raw(value)
        is PropValue.ParamRef -> param(value)
        is PropValue.StateBinding -> binding(value)
        null -> throw CodegenException("float prop has no value")
        else -> throw CodegenException("float prop must be a literal or expression, got $value")
    }

    /** An sp-typed prop (font size, line height) → `N.sp` (or a raw expression). */
    fun sp(value: PropValue?): CodeBlock = when (value) {
        is PropValue.Literal ->
            CodeBlock.of(
                "%L.%M",
                value.value.intOrNull
                    ?: throw CodegenException("sp prop expects an integer, got '${value.value.content}'"),
                ComposeNames.sp,
            )
        is PropValue.RawExpression -> raw(value)
        is PropValue.ParamRef -> param(value)
        is PropValue.StateBinding -> binding(value)
        null -> throw CodegenException("sp prop has no value")
        else -> throw CodegenException("sp prop must be a literal or expression, got $value")
    }

    /** A plain integer prop (e.g. `maxLines`) → `N` (or a raw expression). */
    fun int(value: PropValue?): CodeBlock = when (value) {
        is PropValue.Literal ->
            CodeBlock.of(
                "%L",
                value.value.intOrNull
                    ?: throw CodegenException("int prop expects an integer, got '${value.value.content}'"),
            )
        is PropValue.RawExpression -> raw(value)
        is PropValue.ParamRef -> param(value)
        is PropValue.StateBinding -> binding(value)
        null -> throw CodegenException("int prop has no value")
        else -> throw CodegenException("int prop must be a literal or expression, got $value")
    }

    /** A boolean-typed prop → `true`/`false` (or a raw expression, GC-4). */
    fun bool(value: PropValue?): CodeBlock = when (value) {
        is PropValue.Literal ->
            CodeBlock.of(
                "%L",
                value.value.booleanOrNull
                    ?: throw CodegenException("bool prop expects true/false, got '${value.value.content}'"),
            )
        is PropValue.RawExpression -> raw(value)
        is PropValue.ParamRef -> param(value)
        is PropValue.StateBinding -> binding(value)
        null -> throw CodegenException("bool prop has no value")
        else -> throw CodegenException("bool prop must be a literal or expression, got $value")
    }

    /**
     * A `Text`'s content: a string literal (escaped by KotlinPoet, GC-2) or a raw expression (GC-4). A state
     * binding emits as member access; when [numericBinding] is set (the bound field is INT/FLOAT), it is coerced
     * with `.toString()` so a live number can be shown as text (#298) — the emitter decides via [resolveBindingType].
     */
    fun text(value: PropValue?, numericBinding: Boolean = false): CodeBlock = when (value) {
        null -> CodeBlock.of("%S", "")
        is PropValue.Literal -> CodeBlock.of("%S", value.value.content)
        is PropValue.RawExpression -> raw(value)
        is PropValue.ParamRef -> param(value)
        is PropValue.StateBinding -> stringBinding(value, numericBinding)
        else -> throw CodegenException("Text 'text' must be a string literal or expression, got $value")
    }

    /**
     * A nullable string prop (e.g. an `Image`'s `contentDescription`): a string literal (escaped by
     * KotlinPoet, GC-2), an explicit `null` when absent, or a raw expression (GC-4). A numeric state binding is
     * coerced with `.toString()` (#298), like [text].
     */
    fun nullableString(value: PropValue?, numericBinding: Boolean = false): CodeBlock = when (value) {
        null -> CodeBlock.of("null")
        is PropValue.Literal -> CodeBlock.of("%S", value.value.content)
        is PropValue.RawExpression -> raw(value)
        is PropValue.ParamRef -> param(value)
        is PropValue.StateBinding -> stringBinding(value, numericBinding)
        else -> throw CodegenException("Expected a string literal, null, or expression, got $value")
    }

    /**
     * A [PropValue.StateBinding] into a String-typed prop: bare member access (`count`, `item.name`), or
     * `<path>.toString()` when [numeric] (the bound field is INT/FLOAT) so it satisfies the String parameter (#298).
     */
    private fun stringBinding(value: PropValue.StateBinding, numeric: Boolean): CodeBlock =
        if (numeric) CodeBlock.of("%L.toString()", binding(value)) else binding(value)

    /**
     * An `Image`'s `painter`: a `ResourceRef` resolved to its asset, or a raw expression. The [images] strategy
     * (#223, ADR-021) picks the API: [ImageResources.Desktop] emits the Phase-1 `painterResource("<path>")`
     * (desktop classpath resource); [ImageResources.Multiplatform] emits `painterResource(Res.drawable.<x>)` (the
     * `commonMain` resources API that also renders on Android), the accessor and file agreed via
     * [DrawableResources]. Fails loudly on a reference to an asset the project doesn't list, so codegen never
     * emits a resource that isn't in the exported bundle.
     */
    fun painter(value: PropValue?, assets: Map<String, Asset>, images: ImageResources): CodeBlock = when (value) {
        is PropValue.ResourceRef -> {
            val asset = assets[value.assetId]
                ?: throw CodegenException("Image references unknown asset '${value.assetId}'")
            when (images) {
                is ImageResources.Desktop -> CodeBlock.of("%M(%S)", ComposeNames.painterResource, asset.path)
                is ImageResources.Multiplatform -> CodeBlock.of(
                    // The accessor is a %M (imported member), not a %N (bare name): `Res.drawable.<x>` is an
                    // extension property in the resources package that must be imported, or the KMP build fails to
                    // compile (#322). %T imports `Res`; the second %M imports the accessor.
                    "%M(%T.drawable.%M)",
                    ComposeNames.painterResourceMultiplatform,
                    ComposeNames.resClass(images.resPackage),
                    ComposeNames.drawableAccessor(images.resPackage, DrawableResources.accessor(asset)),
                )
            }
        }
        is PropValue.RawExpression -> raw(value)
        else -> throw CodegenException("Image 'source' must be a resource reference or expression, got $value")
    }

    /** A filled Button's `contentPadding` from a Dp prop → `PaddingValues(N.dp)`. */
    fun contentPadding(value: PropValue?): CodeBlock = CodeBlock.of("%T(%L)", ComposeNames.PaddingValues, dpProp(value))

    /** A filled Button's `elevation` from a Dp prop → `ButtonDefaults.buttonElevation(defaultElevation = N.dp)`. */
    fun buttonElevation(value: PropValue?): CodeBlock =
        CodeBlock.of("%T.buttonElevation(defaultElevation = %L)", ComposeNames.ButtonDefaults, dpProp(value))

    /**
     * A filled Button's `colors` from its optional `containerColor`/`contentColor` props →
     * `ButtonDefaults.buttonColors(...)` naming only the set colors (the rest keep the factory default),
     * or null when neither is set so the caller omits the arg entirely (matching the renderer).
     */
    fun buttonColors(container: PropValue?, content: PropValue?, theme: Theme): CodeBlock? {
        if (container == null && content == null) return null
        val args = buildList {
            container?.let { add(CodeBlock.of("containerColor = %L", color(it, theme))) }
            content?.let { add(CodeBlock.of("contentColor = %L", color(it, theme))) }
        }
        return CodeBlock.of("%T.buttonColors(%L)", ComposeNames.ButtonDefaults, args.joinToCode(", "))
    }

    /** A lambda-valued prop such as `onClick` — only an expression is meaningful; absent → no-op `{}`. */
    fun lambda(value: PropValue?): CodeBlock = when (value) {
        null -> CodeBlock.of("{}")
        is PropValue.RawExpression -> raw(value)
        else -> throw CodegenException("Expected a lambda expression, got $value")
    }

    /**
     * A shape-typed value: a literal corner radius → `RoundedCornerShape(N.dp)`, a
     * `shapes.small|medium|large` token → `MaterialTheme.shapes.<slot>`, a custom project shape token →
     * its resolved `RoundedCornerShape`, or a raw expression. The codegen twin of the renderer's
     * `resolveShape`, so canvas and output agree (ADR-018).
     */
    fun shape(value: PropValue?, theme: Theme): CodeBlock = when (value) {
        is PropValue.Literal -> CodeBlock.of("%M(%L)", ComposeNames.RoundedCornerShape, dpProp(value))
        is PropValue.ThemeRef -> shapeTheme(value.token, theme)
        is PropValue.RawExpression -> raw(value)
        else -> throw CodegenException("Shape prop must be a literal, theme ref, or expression, got $value")
    }

    private fun shapeTheme(token: String, theme: Theme): CodeBlock {
        val slot = token.removePrefix("shapes.")
        if (slot == token) throw CodegenException("Shape prop expects a 'shapes.*' token, got '$token'")
        if (slot in MATERIAL_SHAPE_SLOTS) return CodeBlock.of("%T.shapes.%L", ComposeNames.MaterialTheme, slot)
        // A custom (non-Material) project shape: emit its resolved corner radius so output still compiles.
        val size = theme.shapes[slot]
            ?: throw CodegenException("Unresolved shape token '$token' (not a Material slot nor in the theme)")
        return CodeBlock.of("%M(%L.%M)", ComposeNames.RoundedCornerShape, size, ComposeNames.dp)
    }

    /** A color-typed value: `Color(0x..)` literal, a Material scheme slot, or a themed literal. */
    fun color(value: PropValue?, theme: Theme): CodeBlock = when (value) {
        is PropValue.Literal -> colorLiteral(value.value.content)
        is PropValue.ThemeRef -> colorTheme(value.token, theme)
        is PropValue.RawExpression -> raw(value)
        is PropValue.ParamRef -> param(value)
        is PropValue.StateBinding -> binding(value)
        else -> throw CodegenException("Color prop must be a literal, theme ref, or expression, got $value")
    }

    /**
     * A typography-typed value: a Material `MaterialTheme.typography.<slot>`, a **custom** project
     * token emitted as an inline `TextStyle(...)` (M8 — mirrors the renderer's `resolveTextStyle`), or
     * a raw expression. A custom token's values come from [theme], so a rename/edit stays consistent.
     */
    fun typography(value: PropValue?, theme: Theme): CodeBlock = when (value) {
        is PropValue.ThemeRef -> {
            val slot = value.token.removePrefix("typography.")
            when {
                slot == value.token ->
                    throw CodegenException("Typography prop expects a 'typography.*' token, got '${value.token}'")
                slot in MATERIAL_TYPO_SLOTS -> CodeBlock.of("%T.typography.%L", ComposeNames.MaterialTheme, slot)
                else -> {
                    val token = theme.typography[slot]
                        ?: throw CodegenException(
                            "Unresolved typography token '${value.token}' (not Material nor in theme)",
                        )
                    textStyle(token)
                }
            }
        }
        is PropValue.RawExpression -> raw(value)
        else -> throw CodegenException("Typography prop must be a theme ref or expression, got $value")
    }

    /**
     * An inline `TextStyle(...)` for a project typography [token] — the codegen twin of the renderer's
     * `resolveTextStyle`, so a custom token renders and generates identically. `fontFamily` is omitted
     * (Phase 1 only carries "default"); font size and line height are `sp`, weight a `FontWeight(Int)`.
     */
    fun textStyle(token: TypographyToken): CodeBlock = CodeBlock.of(
        "%T(fontSize = %L.%M, fontWeight = %T(%L), lineHeight = %L.%M)",
        ComposeNames.TextStyle,
        token.fontSize,
        ComposeNames.sp,
        ComposeNames.FontWeight,
        token.fontWeight,
        token.lineHeight,
        ComposeNames.sp,
    )

    /**
     * An alignment/arrangement enum prop → `Alignment.<name>` / `Arrangement.<name>`. Normalizes
     * through the *same* parser the renderer uses, so an out-of-range value resolves identically in
     * both paths (no divergence). Shared with [CatalogConsistencyTest] to prove no enum drift.
     */
    fun enum(propName: String, value: PropValue?): CodeBlock = when (value) {
        is PropValue.Literal -> enumMember(propName, value.value.content)
        is PropValue.RawExpression -> raw(value)
        is PropValue.ParamRef -> param(value)
        is PropValue.StateBinding -> binding(value)
        null -> throw CodegenException("Enum prop '$propName' has no value")
        else -> throw CodegenException("Enum prop '$propName' must be a literal or expression, got $value")
    }

    private fun enumMember(propName: String, name: String): CodeBlock = when (propName) {
        "horizontalAlignment" -> CodeBlock.of("%T.%L", ComposeNames.Alignment, hAlign(name).name)
        "verticalAlignment" -> CodeBlock.of("%T.%L", ComposeNames.Alignment, vAlign(name).name)
        "contentAlignment" -> CodeBlock.of("%T.%L", ComposeNames.Alignment, boxAlign(name).name)
        // `Image`'s alignment shares the Box alignment set (`Alignment.<name>`) and its parser.
        "alignment" -> CodeBlock.of("%T.%L", ComposeNames.Alignment, boxAlign(name).name)
        "verticalArrangement" -> CodeBlock.of("%T.%L", ComposeNames.Arrangement, vArrange(name).name)
        "horizontalArrangement" -> CodeBlock.of("%T.%L", ComposeNames.Arrangement, hArrange(name).name)
        "contentScale" -> CodeBlock.of("%T.%L", ComposeNames.ContentScale, imageScale(name).name)
        // An icon extension property: `Icons.Filled` receiver (imports Icons) + the property (imports it).
        "icon" -> CodeBlock.of("%T.%M", ComposeNames.IconsFilled, ComposeNames.iconMember(iconName(name)))
        "fontWeight" -> CodeBlock.of("%T.%L", ComposeNames.FontWeight, fontWeightName(name))
        "fontStyle" -> CodeBlock.of("%T.%L", ComposeNames.FontStyle, fontStyleName(name))
        "textAlign" -> CodeBlock.of("%T.%L", ComposeNames.TextAlign, textAlignName(name))
        "textDecoration" -> CodeBlock.of("%T.%L", ComposeNames.TextDecoration, textDecorationName(name))
        "overflow" -> CodeBlock.of("%T.%L", ComposeNames.TextOverflow, textOverflowName(name))
        else -> throw CodegenException("Unknown enum prop '$propName'")
    }

    /** A `Color(0xAARRGGBB)` literal from a hex string — shared with [ThemeEmitter] so both agree. */
    fun colorLiteral(hex: String): CodeBlock {
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

    /**
     * A component parameter reference emits the bare parameter identifier (parameters slice 2): inside
     * a component body the value *is* the enclosing function's parameter, so `text = label`, whatever
     * the parameter's declared type. Resolution to a concrete value happens at the instance call site.
     */
    private fun param(value: PropValue.ParamRef): CodeBlock = CodeBlock.of("%N", value.param)

    /**
     * A read-only [PropValue.StateBinding] (ADR-034, #21): the binding's dotted identifier path emitted as
     * **member access** — a scalar screen field `title`, or a repeat item's field `item.title` — using `%N`
     * per segment so it is structural (GC-1/GC-2), never spliced source text, and never evaluated (PF-4). The
     * path is a validated identifier chain (core/model `parseBindingPath`); the seeded state stub declares the
     * `val` this reads, and a repeat's `forEach { item -> … }` binds the `item` scope.
     */
    fun bindingPath(path: String): CodeBlock {
        val segments = path.split('.')
        return CodeBlock.of(segments.joinToString(".") { "%N" }, *segments.toTypedArray())
    }

    private fun binding(value: PropValue.StateBinding): CodeBlock = bindingPath(value.path)
}

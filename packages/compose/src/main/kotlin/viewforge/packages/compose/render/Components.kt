package viewforge.packages.compose.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import viewforge.model.ComponentDef
import viewforge.model.Node
import viewforge.model.PropValue
import viewforge.model.UserComponent

/**
 * The interpreter walk (ARCHITECTURE §4.2): map a node's `type` to a real Compose composable, fold
 * its modifiers, and recurse. Each component owns its own render here so adding one is a local
 * change, never a change to the canvas.
 *
 * Dispatch is by explicit `type` over the currently supported set (FEATURES §2), which grows one
 * component at a time beside its emitter (ADR-018). Anything else draws a visible placeholder rather
 * than being dispatched by name (PF-6): failing loudly beats a silent wrong render (CLAUDE.md).
 */
@Composable
fun RenderNode(node: Node, ctx: RenderContext) {
    if (node.hidden) return // excluded from BOTH render and codegen (DATA_MODEL §5)

    // Fold the node's own (semantic, ordered) chain, then append the editor's instrumentation last so
    // it observes the fully-modified node without altering its layout (ARCHITECTURE §4.2, ADR-009).
    val modifier = buildModifier(node.modifiers, ctx).then(ctx.instrument(node.id))
    when (node.type) {
        "compose.foundation.layout.Column" -> RenderColumn(node, modifier, ctx)
        "compose.foundation.layout.Row" -> RenderRow(node, modifier, ctx)
        "compose.foundation.layout.Box" -> RenderBox(node, modifier, ctx)
        "compose.foundation.layout.Spacer" -> Spacer(modifier)
        "compose.foundation.lazy.LazyColumn" -> RenderLazyColumn(node, modifier, ctx)
        "compose.foundation.lazy.LazyRow" -> RenderLazyRow(node, modifier, ctx)
        "compose.material3.Text" -> RenderText(node, modifier, ctx)
        "compose.material3.Button" -> RenderButton(node, modifier, ctx)
        "compose.material3.OutlinedButton" -> RenderOutlinedButton(node, modifier, ctx)
        "compose.material3.TextButton" -> RenderTextButton(node, modifier, ctx)
        "compose.material3.Slider" -> RenderSlider(node, modifier)
        "compose.material3.TextField" -> RenderTextField(node, modifier)
        "compose.material3.OutlinedTextField" -> RenderOutlinedTextField(node, modifier)
        "compose.material3.CircularProgressIndicator" -> RenderCircularProgress(modifier)
        "compose.material3.LinearProgressIndicator" -> RenderLinearProgress(modifier)
        "compose.material3.Card" -> RenderCard(node, modifier, ctx)
        "compose.material3.Surface" -> RenderSurface(node, modifier, ctx)
        "compose.material3.HorizontalDivider" -> RenderDivider(node, modifier)
        "compose.material3.Checkbox" -> RenderCheckbox(node, modifier)
        "compose.material3.Switch" -> RenderSwitch(node, modifier)
        "compose.foundation.Image" -> RenderImage(node, modifier, ctx)
        "compose.material3.Icon" -> RenderIcon(node, modifier)
        "compose.material3.TopAppBar" -> RenderTopAppBar(node, modifier, ctx)
        "compose.material3.BottomAppBar" -> RenderBottomAppBar(node, modifier, ctx)
        "compose.material3.Scaffold" -> RenderScaffold(node, modifier, ctx)
        UserComponent.TYPE -> RenderUserComponent(node, modifier, ctx)
        else -> ErrorPlaceholder("Unsupported component:\n${node.type}", modifier)
    }
}

/** The outcome of resolving a `vforge.userComponent` instance against the available definitions. */
internal sealed interface InstanceResolution {
    /** The instance references [def], which can be drawn. */
    data class Resolved(val def: ComponentDef) : InstanceResolution

    /** No component matches the instance's `componentId` ([id] is that value, or null if the prop is absent). */
    data class Missing(val id: String?) : InstanceResolution

    /** [def] is already mid-render above this instance — drawing it would recurse forever (PF-3). */
    data class Cycle(val def: ComponentDef) : InstanceResolution
}

/**
 * Resolve a `vforge.userComponent` instance [node] to its definition, or report why it can't be drawn.
 * Pure so the decision is unit-testable without a composition; [RenderUserComponent] draws each outcome
 * (ADR-024).
 */
internal fun resolveUserComponent(
    node: Node,
    components: Map<String, ComponentDef>,
    expanding: Set<String>,
): InstanceResolution {
    val id = (node.props[UserComponent.COMPONENT_ID_PROP] as? PropValue.Literal)?.value?.content
    val def = id?.let { components[it] } ?: return InstanceResolution.Missing(id)
    return if (def.id in expanding) InstanceResolution.Cycle(def) else InstanceResolution.Resolved(def)
}

/**
 * A `vforge.userComponent` instance draws the component it references (ADR-024): render its root inside
 * a [Box] carrying the instance's own [modifier], so the instance selects and is instrumented as a
 * single unit on the canvas while its internals are not — those are edited by opening the component, not
 * through the instance. A missing reference or a cycle draws a loud placeholder rather than a blank or
 * an infinite recursion (PF-6).
 */
@Composable
private fun RenderUserComponent(node: Node, modifier: Modifier, ctx: RenderContext) {
    when (val resolution = resolveUserComponent(node, ctx.components, ctx.expanding)) {
        is InstanceResolution.Missing -> ErrorPlaceholder("Unresolved component:\n${resolution.id ?: "?"}", modifier)
        is InstanceResolution.Cycle -> ErrorPlaceholder("Component cycle:\n${resolution.def.name}", modifier)
        is InstanceResolution.Resolved -> Box(modifier) {
            // The internals are not the active tree's nodes: suppress per-node instrumentation so a click
            // selects the instance (the Box above), and mark this id as expanding to break any cycle.
            RenderNode(
                resolution.def.root,
                ctx.copy(expanding = ctx.expanding + resolution.def.id, instrument = { Modifier }),
            )
        }
    }
}

@Composable
private fun RenderChildren(nodes: List<Node>, ctx: RenderContext) {
    // Key by stable node id so reorders move composables rather than recreating them (TECHNICAL_NOTES §4).
    nodes.forEach { child -> key(child.id.value) { RenderNode(child, ctx) } }
}

@Composable
private fun RenderColumn(node: Node, modifier: Modifier, ctx: RenderContext) {
    Column(
        modifier = modifier,
        verticalArrangement = vArrange(node.props["verticalArrangement"].literalString()).toCompose(),
        horizontalAlignment = hAlign(node.props["horizontalAlignment"].literalString()).toCompose(),
    ) {
        RenderChildren(node.children, ctx)
    }
}

@Composable
private fun RenderRow(node: Node, modifier: Modifier, ctx: RenderContext) {
    Row(
        modifier = modifier,
        horizontalArrangement = hArrange(node.props["horizontalArrangement"].literalString()).toCompose(),
        verticalAlignment = vAlign(node.props["verticalAlignment"].literalString()).toCompose(),
    ) {
        RenderChildren(node.children, ctx)
    }
}

@Composable
private fun RenderBox(node: Node, modifier: Modifier, ctx: RenderContext) {
    Box(
        modifier = modifier,
        contentAlignment = boxAlign(node.props["contentAlignment"].literalString()).toCompose(),
    ) {
        RenderChildren(node.children, ctx)
    }
}

@Composable
private fun RenderLazyColumn(node: Node, modifier: Modifier, ctx: RenderContext) {
    // Phase-1 lists are static children only (DATA_MODEL §12.2) — each child is one `item`, keyed by
    // its stable node id so reorders move composables rather than recreating them (TECHNICAL_NOTES §4).
    LazyColumn(
        modifier = modifier,
        verticalArrangement = vArrange(node.props["verticalArrangement"].literalString()).toCompose(),
        horizontalAlignment = hAlign(node.props["horizontalAlignment"].literalString()).toCompose(),
    ) {
        node.children.forEach { child -> item(key = child.id.value) { RenderNode(child, ctx) } }
    }
}

@Composable
private fun RenderLazyRow(node: Node, modifier: Modifier, ctx: RenderContext) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = hArrange(node.props["horizontalArrangement"].literalString()).toCompose(),
        verticalAlignment = vAlign(node.props["verticalAlignment"].literalString()).toCompose(),
    ) {
        node.children.forEach { child -> item(key = child.id.value) { RenderNode(child, ctx) } }
    }
}

@Composable
private fun RenderText(node: Node, modifier: Modifier, ctx: RenderContext) {
    // Each optional value falls back to the same default the Text composable uses when the arg is
    // omitted, so an unset prop renders identically to codegen omitting it (TECHNICAL_NOTES §2).
    Text(
        text = node.props["text"].literalString() ?: "",
        modifier = modifier,
        color = resolveColor(node.props["color"], ctx) ?: Color.Unspecified,
        fontSize = node.props["fontSize"].literalInt()?.sp ?: TextUnit.Unspecified,
        fontStyle = node.props["fontStyle"].literalString()?.let { fontStyleOf(it) },
        fontWeight = node.props["fontWeight"].literalString()?.let { fontWeightOf(it) },
        letterSpacing = node.props["letterSpacing"].literalInt()?.sp ?: TextUnit.Unspecified,
        textDecoration = node.props["textDecoration"].literalString()?.let { textDecorationOf(it) },
        textAlign = node.props["textAlign"].literalString()?.let { textAlignOf(it) },
        lineHeight = node.props["lineHeight"].literalInt()?.sp ?: TextUnit.Unspecified,
        overflow = node.props["overflow"].literalString()?.let { textOverflowOf(it) } ?: TextOverflow.Clip,
        maxLines = node.props["maxLines"].literalInt() ?: Int.MAX_VALUE,
        style = resolveTextStyle(node.props["style"], ctx),
    )
}

private fun fontWeightOf(name: String): FontWeight = when (fontWeightName(name)) {
    "Light" -> FontWeight.Light
    "Medium" -> FontWeight.Medium
    "SemiBold" -> FontWeight.SemiBold
    "Bold" -> FontWeight.Bold
    else -> FontWeight.Normal
}

private fun fontStyleOf(name: String): FontStyle = when (fontStyleName(name)) {
    "Italic" -> FontStyle.Italic
    else -> FontStyle.Normal
}

private fun textDecorationOf(name: String): TextDecoration = when (textDecorationName(name)) {
    "Underline" -> TextDecoration.Underline
    "LineThrough" -> TextDecoration.LineThrough
    else -> TextDecoration.None
}

private fun textAlignOf(name: String): TextAlign = when (textAlignName(name)) {
    "Center" -> TextAlign.Center
    "End" -> TextAlign.End
    "Justify" -> TextAlign.Justify
    else -> TextAlign.Start
}

private fun textOverflowOf(name: String): TextOverflow = when (textOverflowName(name)) {
    "Ellipsis" -> TextOverflow.Ellipsis
    "Visible" -> TextOverflow.Visible
    else -> TextOverflow.Clip
}

@Composable
private fun RenderButton(node: Node, modifier: Modifier, ctx: RenderContext) {
    // `onClick` is a RawExpression escape hatch — never evaluated on the canvas (PF-4); a no-op here.
    // Absent styling props pass Compose's own defaults explicitly, so the canvas matches codegen — which
    // simply omits those args and thus gets the same defaults (TECHNICAL_NOTES §2).
    Button(
        onClick = {},
        modifier = modifier,
        enabled = node.props["enabled"].literalBoolean() ?: true,
        shape = resolveShape(node.props["shape"], ctx) ?: ButtonDefaults.shape,
        colors = buttonColorsFor(node, ctx),
        elevation = node.props["elevation"].literalInt()
            ?.let { ButtonDefaults.buttonElevation(defaultElevation = it.dp) }
            ?: ButtonDefaults.buttonElevation(),
        contentPadding = node.props["contentPadding"].literalInt()
            ?.let { PaddingValues(it.dp) }
            ?: ButtonDefaults.ContentPadding,
    ) {
        RenderChildren(node.slots["content"].orEmpty(), ctx)
    }
}

/**
 * The filled Button's `colors`, built from the optional `containerColor`/`contentColor` props. Only the
 * set colors are overridden (the rest keep `ButtonDefaults.buttonColors()`), mirroring codegen which
 * emits `ButtonDefaults.buttonColors(...)` with exactly the set args.
 */
@Composable
private fun buttonColorsFor(node: Node, ctx: RenderContext): ButtonColors {
    val container = resolveColor(node.props["containerColor"], ctx)
    val content = resolveColor(node.props["contentColor"], ctx)
    return when {
        container != null && content != null ->
            ButtonDefaults.buttonColors(containerColor = container, contentColor = content)
        container != null -> ButtonDefaults.buttonColors(containerColor = container)
        content != null -> ButtonDefaults.buttonColors(contentColor = content)
        else -> ButtonDefaults.buttonColors()
    }
}

@Composable
private fun RenderOutlinedButton(node: Node, modifier: Modifier, ctx: RenderContext) {
    OutlinedButton(onClick = {}, modifier = modifier, enabled = node.props["enabled"].literalBoolean() ?: true) {
        RenderChildren(node.slots["content"].orEmpty(), ctx)
    }
}

@Composable
private fun RenderTextButton(node: Node, modifier: Modifier, ctx: RenderContext) {
    TextButton(onClick = {}, modifier = modifier, enabled = node.props["enabled"].literalBoolean() ?: true) {
        RenderChildren(node.slots["content"].orEmpty(), ctx)
    }
}

@Composable
private fun RenderSlider(node: Node, modifier: Modifier) {
    // `onValueChange` is a RawExpression escape hatch — never evaluated on the canvas (PF-4); a no-op here.
    Slider(
        value = node.props["value"].literalFloat() ?: 0f,
        onValueChange = {},
        modifier = modifier,
        enabled = node.props["enabled"].literalBoolean() ?: true,
    )
}

@Composable
private fun RenderTextField(node: Node, modifier: Modifier) {
    // `onValueChange` is a RawExpression escape hatch — never evaluated on the canvas (PF-4); the field
    // reflects the `value` prop but isn't interactively editable here (edit `value` in the inspector).
    TextField(
        value = node.props["value"].literalString() ?: "",
        onValueChange = {},
        modifier = modifier,
        enabled = node.props["enabled"].literalBoolean() ?: true,
    )
}

@Composable
private fun RenderOutlinedTextField(node: Node, modifier: Modifier) {
    OutlinedTextField(
        value = node.props["value"].literalString() ?: "",
        onValueChange = {},
        modifier = modifier,
        enabled = node.props["enabled"].literalBoolean() ?: true,
    )
}

@Composable
private fun RenderCircularProgress(modifier: Modifier) {
    // Indeterminate (no bound progress) — the Phase-1 "loading indicator".
    CircularProgressIndicator(modifier = modifier)
}

@Composable
private fun RenderLinearProgress(modifier: Modifier) {
    LinearProgressIndicator(modifier = modifier)
}

@Composable
private fun RenderCard(node: Node, modifier: Modifier, ctx: RenderContext) {
    Card(modifier = modifier) { RenderChildren(node.children, ctx) }
}

@Composable
private fun RenderSurface(node: Node, modifier: Modifier, ctx: RenderContext) {
    Surface(modifier = modifier) { RenderChildren(node.children, ctx) }
}

@Composable
private fun RenderDivider(node: Node, modifier: Modifier) {
    // `thickness` absent falls back to Material's 1.dp default, which is exactly what codegen emits
    // when the prop is unset — so canvas and generated output agree either way.
    HorizontalDivider(modifier = modifier, thickness = (node.props["thickness"].literalInt() ?: 1).dp)
}

@Composable
private fun RenderCheckbox(node: Node, modifier: Modifier) {
    // `onCheckedChange` is a RawExpression escape hatch — never evaluated on the canvas (PF-4); a no-op here.
    Checkbox(
        checked = node.props["checked"].literalBoolean() ?: false,
        onCheckedChange = {},
        modifier = modifier,
        enabled = node.props["enabled"].literalBoolean() ?: true,
    )
}

@Composable
private fun RenderSwitch(node: Node, modifier: Modifier) {
    Switch(
        checked = node.props["checked"].literalBoolean() ?: false,
        onCheckedChange = {},
        modifier = modifier,
        enabled = node.props["enabled"].literalBoolean() ?: true,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenderTopAppBar(node: Node, modifier: Modifier, ctx: RenderContext) {
    TopAppBar(
        title = { RenderChildren(node.slots["title"].orEmpty(), ctx) },
        modifier = modifier,
    )
}

@Composable
private fun RenderBottomAppBar(node: Node, modifier: Modifier, ctx: RenderContext) {
    BottomAppBar(modifier = modifier) { RenderChildren(node.children, ctx) }
}

@Composable
private fun RenderScaffold(node: Node, modifier: Modifier, ctx: RenderContext) {
    Scaffold(
        modifier = modifier,
        topBar = { RenderChildren(node.slots["topBar"].orEmpty(), ctx) },
        bottomBar = { RenderChildren(node.slots["bottomBar"].orEmpty(), ctx) },
    ) { innerPadding ->
        // Wrap content in a padded Column so it consumes the scaffold inset — the exact structure
        // codegen emits, so canvas and generated output agree (TECHNICAL_NOTES §2).
        Column(modifier = Modifier.padding(innerPadding)) {
            RenderChildren(node.children, ctx)
        }
    }
}

@Composable
private fun RenderIcon(node: Node, modifier: Modifier) {
    Icon(
        imageVector = iconVector(node.props["icon"].literalString()),
        contentDescription = node.props["contentDescription"].literalString(),
        modifier = modifier,
    )
}

/** Maps a curated icon name to its `Icons.Filled` vector — kept in lockstep with [ICON_NAMES]. */
private fun iconVector(name: String?): ImageVector = when (iconName(name)) {
    "Home" -> Icons.Filled.Home
    "Settings" -> Icons.Filled.Settings
    "Search" -> Icons.Filled.Search
    "Menu" -> Icons.Filled.Menu
    "Close" -> Icons.Filled.Close
    "Check" -> Icons.Filled.Check
    "Add" -> Icons.Filled.Add
    "Delete" -> Icons.Filled.Delete
    "Edit" -> Icons.Filled.Edit
    "Favorite" -> Icons.Filled.Favorite
    "Info" -> Icons.Filled.Info
    "Warning" -> Icons.Filled.Warning
    "ArrowBack" -> Icons.Filled.ArrowBack
    "ArrowForward" -> Icons.Filled.ArrowForward
    "Person" -> Icons.Filled.Person
    "Share" -> Icons.Filled.Share
    "ShoppingCart" -> Icons.Filled.ShoppingCart
    "Refresh" -> Icons.Filled.Refresh
    "MoreVert" -> Icons.Filled.MoreVert
    else -> Icons.Filled.Star
}

@Composable
private fun RenderImage(node: Node, modifier: Modifier, ctx: RenderContext) {
    // The source is a ResourceRef; the editor's imageLoader turns its asset id into a decoded bitmap.
    // A missing/unresolvable asset draws a loud placeholder rather than a blank (ARCHITECTURE §9) — the
    // canvas never silently omits an image the generated code would still emit.
    val assetId = (node.props["source"] as? PropValue.ResourceRef)?.assetId
    val bitmap = assetId?.let { ctx.imageLoader(it) }
    if (bitmap == null) {
        ErrorPlaceholder("Missing image", modifier)
        return
    }
    // Each optional arg falls back to the same default the Image composable uses when omitted, so an
    // unset prop renders identically to codegen omitting it (TECHNICAL_NOTES §2): alignment→Center,
    // alpha→1f (Compose's DefaultAlpha).
    Image(
        painter = BitmapPainter(bitmap),
        contentDescription = node.props["contentDescription"].literalString(),
        modifier = modifier,
        alignment = node.props["alignment"].literalString()?.let { boxAlign(it).toCompose() } ?: Alignment.Center,
        contentScale = imageScale(node.props["contentScale"].literalString()).toCompose(),
        alpha = node.props["alpha"].literalFloat() ?: 1f,
    )
}

/** A visible marker for a node the canvas can't render — never a blank space (ARCHITECTURE §9). */
@Composable
private fun ErrorPlaceholder(message: String, modifier: Modifier) {
    Box(
        modifier
            .border(1.dp, Color(0xFFB00020))
            .padding(6.dp),
    ) {
        Text(text = message, color = Color(0xFFB00020), fontSize = 11.sp)
    }
}

// --- Prop resolution needing composition (Material fallbacks read MaterialTheme) ------------------

@Composable
private fun resolveColor(value: PropValue?, ctx: RenderContext): Color? = when (value) {
    is PropValue.Literal -> parseColorArgb(value.value.content)?.let { Color(it) }
    is PropValue.ThemeRef -> {
        val hex = resolveColorHex(ctx.theme, value.token, ctx.dark)
        if (hex != null) parseColorArgb(hex)?.let { Color(it) } else materialColorToken(value.token)
    }
    else -> null // RawExpression/binding aren't evaluable on the canvas
}

/**
 * Resolves a `shape` prop to a Compose [Shape], mirroring codegen's `CodegenValues.shape`: a literal
 * corner radius → `RoundedCornerShape(N.dp)`, a `shapes.small|medium|large` token → the Material slot
 * (already carrying any project override via `projectShapes`, ADR-018), a custom project shape token →
 * its `RoundedCornerShape`. Null (unset/expression) lets the caller fall back to the composable default.
 */
@Composable
private fun resolveShape(value: PropValue?, ctx: RenderContext): Shape? = when (value) {
    is PropValue.Literal -> value.literalInt()?.let { RoundedCornerShape(it.dp) }
    is PropValue.ThemeRef -> {
        val slot = value.token.removePrefix("shapes.")
        when {
            slot == value.token -> null // not a shapes.* token
            slot in MATERIAL_SHAPE_SLOTS -> materialShapeToken(slot)
            else -> ctx.theme.shapes[slot]?.let { RoundedCornerShape(it.dp) }
        }
    }
    else -> null // Literal-less kinds (RawExpression/binding) aren't resolvable on the canvas
}

@Composable
private fun materialShapeToken(slot: String): Shape = when (slot) {
    "small" -> MaterialTheme.shapes.small
    "large" -> MaterialTheme.shapes.large
    else -> MaterialTheme.shapes.medium
}

@Composable
private fun materialColorToken(token: String): Color? = when (token.removePrefix("colors.")) {
    "primary" -> MaterialTheme.colorScheme.primary
    "onPrimary" -> MaterialTheme.colorScheme.onPrimary
    "secondary" -> MaterialTheme.colorScheme.secondary
    "onSecondary" -> MaterialTheme.colorScheme.onSecondary
    "background" -> MaterialTheme.colorScheme.background
    "onBackground" -> MaterialTheme.colorScheme.onBackground
    "surface" -> MaterialTheme.colorScheme.surface
    "onSurface" -> MaterialTheme.colorScheme.onSurface
    "error" -> MaterialTheme.colorScheme.error
    else -> null
}

@Composable
private fun resolveTextStyle(value: PropValue?, ctx: RenderContext): TextStyle {
    if (value is PropValue.ThemeRef) {
        val name = typographyTokenName(value.token) ?: return LocalTextStyle.current
        val token = ctx.theme.typography[name]
        if (token != null) {
            return TextStyle(
                fontSize = token.fontSize.sp,
                fontWeight = FontWeight(token.fontWeight),
                lineHeight = token.lineHeight.sp,
            )
        }
        return materialTextStyle(name)
    }
    return LocalTextStyle.current
}

@Composable
private fun materialTextStyle(name: String): TextStyle = when (name) {
    "displayLarge" -> MaterialTheme.typography.displayLarge
    "headlineLarge" -> MaterialTheme.typography.headlineLarge
    "headlineMedium" -> MaterialTheme.typography.headlineMedium
    "titleLarge" -> MaterialTheme.typography.titleLarge
    "titleMedium" -> MaterialTheme.typography.titleMedium
    "bodyLarge" -> MaterialTheme.typography.bodyLarge
    "bodyMedium" -> MaterialTheme.typography.bodyMedium
    "labelLarge" -> MaterialTheme.typography.labelLarge
    else -> LocalTextStyle.current
}

// --- Alignment / arrangement enum → Compose (the one place these map) -----------------------------

private fun HAlign.toCompose(): Alignment.Horizontal = when (this) {
    HAlign.Start -> Alignment.Start
    HAlign.CenterHorizontally -> Alignment.CenterHorizontally
    HAlign.End -> Alignment.End
}

private fun VAlign.toCompose(): Alignment.Vertical = when (this) {
    VAlign.Top -> Alignment.Top
    VAlign.CenterVertically -> Alignment.CenterVertically
    VAlign.Bottom -> Alignment.Bottom
}

private fun VArrange.toCompose(): Arrangement.Vertical = when (this) {
    VArrange.Top -> Arrangement.Top
    VArrange.Center -> Arrangement.Center
    VArrange.Bottom -> Arrangement.Bottom
    VArrange.SpaceBetween -> Arrangement.SpaceBetween
    VArrange.SpaceAround -> Arrangement.SpaceAround
    VArrange.SpaceEvenly -> Arrangement.SpaceEvenly
}

private fun HArrange.toCompose(): Arrangement.Horizontal = when (this) {
    HArrange.Start -> Arrangement.Start
    HArrange.Center -> Arrangement.Center
    HArrange.End -> Arrangement.End
    HArrange.SpaceBetween -> Arrangement.SpaceBetween
    HArrange.SpaceAround -> Arrangement.SpaceAround
    HArrange.SpaceEvenly -> Arrangement.SpaceEvenly
}

private fun ImageScale.toCompose(): ContentScale = when (this) {
    ImageScale.Fit -> ContentScale.Fit
    ImageScale.Crop -> ContentScale.Crop
    ImageScale.FillBounds -> ContentScale.FillBounds
    ImageScale.Inside -> ContentScale.Inside
    ImageScale.None -> ContentScale.None
}

private fun BoxAlign.toCompose(): Alignment = when (this) {
    BoxAlign.TopStart -> Alignment.TopStart
    BoxAlign.TopCenter -> Alignment.TopCenter
    BoxAlign.TopEnd -> Alignment.TopEnd
    BoxAlign.CenterStart -> Alignment.CenterStart
    BoxAlign.Center -> Alignment.Center
    BoxAlign.CenterEnd -> Alignment.CenterEnd
    BoxAlign.BottomStart -> Alignment.BottomStart
    BoxAlign.BottomCenter -> Alignment.BottomCenter
    BoxAlign.BottomEnd -> Alignment.BottomEnd
}

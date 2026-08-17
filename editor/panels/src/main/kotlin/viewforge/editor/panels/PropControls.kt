package viewforge.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import viewforge.model.Asset
import viewforge.model.PropType
import viewforge.model.PropValue
import viewforge.model.Theme

/**
 * The typed, data-driven inspector controls (M5, I2). Each [PropType] maps to exactly one control —
 * text field, numeric stepper, switch, enum dropdown, color editor, typography picker — and there is
 * **no per-component branching** (I1): a new component gets editors for free from its schema.
 *
 * Controls commit a new [PropValue] (or null to clear/reset) through [onChange], which the inspector
 * routes to a command; the canvas updates live because the document is Compose state (I5). A value
 * that is a [PropValue.RawExpression] always shows the expression editor and an "unverified" marker,
 * regardless of the declared type (I6); the code is only ever displayed and passed through, never run.
 */

/** Editor for one node prop, driven by its [PropType] and the current [value]. */
@Composable
internal fun ValueControl(
    type: PropType,
    value: PropValue?,
    theme: Theme,
    onChange: (PropValue?) -> Unit,
    enumValues: List<String>? = null,
    range: ClosedFloatingPointRange<Float>? = null,
    themeable: Boolean = false,
    assets: List<Asset> = emptyList(),
) {
    // An expression value overrides the typed control — it's the escape hatch (I6).
    if (value is PropValue.RawExpression) {
        ExpressionField(value.code, onChange)
        return
    }
    // A prop bound to a component parameter shows its binding read-only (ADR-028); the value comes from
    // the instance's argument, edited on the instance, not here. Undo unbinds it (a promote is one step).
    if (value is PropValue.ParamRef) {
        ParamRefChip(value.param)
        return
    }
    when (type) {
        PropType.Bool -> BoolControl(value.literalBool() ?: false, onChange)
        PropType.Enum -> EnumDropdown(enumValues.orEmpty(), value.literalText(), onChange)
        PropType.Color -> ColorControl(value, theme, themeable, onChange)
        PropType.Typography -> TypographyDropdown(value.themeToken(), theme, onChange)
        PropType.Shape -> ShapeControl(value, onChange)
        PropType.Dp -> NumberField(value.literalText(), range, isInt = true, suffix = "dp", onChange = onChange)
        PropType.Int -> NumberField(value.literalText(), range, isInt = true, onChange = onChange)
        PropType.Float -> NumberField(value.literalText(), range, isInt = false, onChange = onChange)
        PropType.String -> StringField(value.literalText() ?: "", onChange)
        PropType.Resource -> ResourceDropdown(assets, (value as? PropValue.ResourceRef)?.assetId, onChange)
    }
}

// --- primitive controls --------------------------------------------------------------------------

@Composable
private fun StringField(current: String, onChange: (PropValue?) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    FieldBox {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(stringValue(it))
            },
            singleLine = true,
            textStyle = fieldStyle(),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NumberField(
    current: String?,
    range: ClosedFloatingPointRange<Float>?,
    isInt: Boolean,
    onChange: (PropValue?) -> Unit,
    suffix: String? = null,
) {
    var text by remember(current) { mutableStateOf(current ?: "") }
    var invalid by remember(current) { mutableStateOf(false) }
    FieldBox(error = invalid) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = { input ->
                    text = input
                    val result = if (isInt) parseIntInput(input, range) else parseFloatInput(input, range)
                    when (result) {
                        is NumberResult.Valid -> {
                            invalid = false
                            onChange(result.value)
                        }
                        NumberResult.Cleared -> {
                            invalid = false
                            onChange(null)
                        }
                        NumberResult.Invalid -> invalid = true
                    }
                },
                singleLine = true,
                textStyle = fieldStyle(),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
            )
            if (suffix != null) {
                Text(
                    suffix,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (invalid) {
        val msg = if (range != null) {
            "Enter a number in ${range.start.toInt()}–${range.endInclusive.toInt()}"
        } else {
            "Enter a number"
        }
        ErrorText(msg)
    }
}

@Composable
private fun BoolControl(on: Boolean, onChange: (PropValue?) -> Unit) {
    Switch(
        checked = on,
        onCheckedChange = { onChange(boolValue(it)) },
        colors = SwitchDefaults.colors(),
    )
}

@Composable
private fun EnumDropdown(options: List<String>, current: String?, onChange: (PropValue?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FieldBox(onClick = { open = true }) {
            Text(
                text = current ?: "—",
                style = fieldStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onChange(stringValue(option))
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * Picks an [Image] source from the project's already-imported [assets] (I2, data-driven). Importing
 * files from disk into the project is a focused follow-up (see FEATURES §5 / ADR-021); this control
 * assigns among assets the project already carries and shows a hint when there are none.
 */
@Composable
private fun ResourceDropdown(assets: List<Asset>, currentId: String?, onChange: (PropValue?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = assets.firstOrNull { it.id == currentId }
    val label = current?.originalName ?: current?.path ?: currentId ?: "—"
    Box {
        FieldBox(onClick = { open = true }) {
            Text(
                text = if (assets.isEmpty()) "No assets imported" else label,
                style = fieldStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            assets.forEach { asset ->
                DropdownMenuItem(
                    text = { Text(asset.originalName ?: asset.path, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onChange(resourceValue(asset.id))
                        open = false
                    },
                )
            }
        }
    }
}

// --- color -------------------------------------------------------------------------------------

@Composable
private fun ColorControl(value: PropValue?, theme: Theme, themeable: Boolean, onChange: (PropValue?) -> Unit) {
    val token = value.themeToken()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Swatch(previewArgb(value, theme))
            Box(Modifier.weight(1f).padding(start = 6.dp)) {
                if (token != null) {
                    Text("→ $token", style = fieldStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    HexField(value.literalText().orEmpty(), onChange)
                }
            }
            if (themeable) TokenToggle(theme, boundToken = token, onChange = onChange)
        }
    }
}

@Composable
private fun HexField(current: String, onChange: (PropValue?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    var invalid by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // Reflect an external change to the value (undo, selecting another node) only while the field is NOT
    // being edited. Keying the text on `current` instead re-seeded it from our own committed echo, which
    // rewrote a valid shorthand (00F) into its expansion (#0000FF) mid-typing (#188).
    LaunchedEffect(current, focused) {
        if (!focused && text != current) {
            text = current
            invalid = false
        }
    }
    FieldBox(error = invalid) {
        BasicTextField(
            value = text,
            onValueChange = { input ->
                text = input
                val normalized = normalizeHex(input)
                invalid = normalized == null
                if (normalized != null) onChange(stringValue(normalized))
            },
            singleLine = true,
            textStyle = fieldStyle(),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
    }
}

/** A menu that binds this color to a theme token (I4), or unbinds back to a literal. */
@Composable
private fun TokenToggle(theme: Theme, boundToken: String?, onChange: (PropValue?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            text = "🎨",
            style = MaterialTheme.typography.labelSmall,
            color = if (boundToken != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = 4.dp).clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            theme.colors.keys.forEach { name ->
                DropdownMenuItem(
                    text = { Text("colors.$name", style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onChange(themeValue("colors.$name"))
                        open = false
                    },
                )
            }
            if (boundToken != null) {
                DropdownMenuItem(
                    text = { Text("Use a literal color", style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onChange(stringValue("#000000"))
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Swatch(argb: Long?) {
    Box(
        Modifier
            .size(16.dp)
            .background(if (argb != null) Color(argb) else Color.Transparent)
            .border(1.dp, MaterialTheme.colorScheme.outline),
    )
}

// --- typography --------------------------------------------------------------------------------

@Composable
private fun TypographyDropdown(current: String?, theme: Theme, onChange: (PropValue?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val tokens = (theme.typography.keys.map { "typography.$it" } + MATERIAL_TYPOGRAPHY).distinct()
    Box {
        FieldBox(onClick = { open = true }) {
            Text(
                current ?: "—",
                style = fieldStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tokens.forEach { token ->
                DropdownMenuItem(
                    text = { Text(token.removePrefix("typography."), style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onChange(themeValue(token))
                        open = false
                    },
                )
            }
        }
    }
}

// --- shape -------------------------------------------------------------------------------------

/**
 * A Material shape: a literal corner radius (dp) or a `shapes.small|medium|large` theme token. Mirrors
 * [ColorControl] — a value field when unbound, a `→ token` label when bound, and a 🔷 toggle to switch
 * between the two (I4). The three Material slots always resolve via `MaterialTheme.shapes`, so they are
 * always offered (like the Material typography names).
 */
@Composable
private fun ShapeControl(value: PropValue?, onChange: (PropValue?) -> Unit) {
    val token = value.themeToken()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (token != null) {
                Text("→ $token", style = fieldStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                NumberField(value.literalText(), range = null, isInt = true, suffix = "dp corner", onChange = onChange)
            }
        }
        ShapeTokenToggle(boundToken = token, onChange = onChange)
    }
}

/** Binds this shape to a Material shape token (I4), or unbinds back to a literal corner radius. */
@Composable
private fun ShapeTokenToggle(boundToken: String?, onChange: (PropValue?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            text = "🔷",
            style = MaterialTheme.typography.labelSmall,
            color = if (boundToken != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = 4.dp).clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MATERIAL_SHAPES.forEach { name ->
                DropdownMenuItem(
                    text = { Text("shapes.$name", style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onChange(themeValue("shapes.$name"))
                        open = false
                    },
                )
            }
            if (boundToken != null) {
                DropdownMenuItem(
                    text = { Text("Use a literal radius", style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onChange(intValue(8))
                        open = false
                    },
                )
            }
        }
    }
}

// --- expression (escape hatch, I6) --------------------------------------------------------------

@Composable
private fun ExpressionField(code: String, onChange: (PropValue?) -> Unit) {
    var text by remember(code) { mutableStateOf(code) }
    Column {
        FieldBox {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    onChange(expressionValue(it))
                },
                textStyle = fieldStyle().copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "unverified expression",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Read-only display of a prop bound to a component parameter (ADR-028): `→ param: name`. */
@Composable
private fun ParamRefChip(name: String) {
    FieldBox {
        Text(
            "→ param: $name",
            style = fieldStyle(),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// --- shared bits -------------------------------------------------------------------------------

@Composable
private fun FieldBox(error: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val base = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
    val bordered = if (error) base.border(1.dp, MaterialTheme.colorScheme.error, MaterialTheme.shapes.small) else base
    val clickable = if (onClick != null) bordered.clickable(onClick = onClick) else bordered
    Box(clickable.padding(horizontal = 8.dp, vertical = 6.dp)) { content() }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun fieldStyle() = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface)

/** A preview color for the swatch: the literal hex, or the theme token resolved against the light value. */
private fun previewArgb(value: PropValue?, theme: Theme): Long? = when (value) {
    is PropValue.Literal -> hexToArgb(value.value.content)
    is PropValue.ThemeRef -> theme.colors[value.token.removePrefix("colors.")]?.let { hexToArgb(it.light) }
    else -> null
}

/** The Material shape slots, always resolvable via `MaterialTheme.shapes` (see `render/Components.kt`). */
private val MATERIAL_SHAPES = listOf("small", "medium", "large")

/** Material typography names the renderer understands as fallbacks (see `render/Components.kt`). */
private val MATERIAL_TYPOGRAPHY = listOf(
    "typography.displayLarge",
    "typography.headlineLarge",
    "typography.headlineMedium",
    "typography.titleLarge",
    "typography.titleMedium",
    "typography.bodyLarge",
    "typography.bodyMedium",
    "typography.labelLarge",
)

package viewforge.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import viewforge.editor.state.EditorState

/**
 * The project theme editor (M8, H1/H2/H5), a modal dialog opened from the toolbar. It is a plain
 * data-driven view over the four token groups on [EditorState.theme]; every edit routes through the
 * editor's theme API, so it is undoable and the canvas updates live (edits are commands, ADR-017).
 *
 * The dialog wears the **editor chrome** theme (dark), like [viewforge.editor.shell.EditorShell] —
 * deliberately separate from the *project* theme it edits (FEATURES S3). A light/dark preview of the
 * project theme itself is the canvas's job, toggled from the toolbar (H2).
 */
@Composable
fun ThemeEditor(state: EditorState, onClose: () -> Unit) {
    DialogWindow(
        onCloseRequest = onClose,
        title = "Theme — ${state.document.name}",
        // Unspecified size makes the window pack to its content (#162): a fixed height left the dark
        // chrome shorter than the window, exposing the native (white) background below it. The content
        // fixes its own width and caps its height (see [ThemeEditorContent]), so the packed window is
        // exactly as tall as it needs to be, scrolling only when a token-heavy theme exceeds the cap.
        state = rememberDialogState(size = DpSize(Dp.Unspecified, Dp.Unspecified)),
    ) {
        ThemeEditorContent(state)
    }
}

/**
 * The dialog's body, split out so it can be rendered in a test without spawning a real window. Fixes its
 * own width and caps its height with a scroll fallback, so the enclosing packed window sizes to it exactly
 * — no oversized window, no uncovered white gap (#162).
 */
@Composable
internal fun ThemeEditorContent(state: EditorState) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface {
            Column(
                Modifier
                    .width(560.dp)
                    // Cap the height so a token-heavy theme scrolls instead of growing a giant window;
                    // shorter themes wrap below this and the window packs to them.
                    .heightIn(max = 680.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                ColorsSection(state)
                TypographySection(state)
                ShapesSection(state)
                SpacingSection(state)
            }
        }
    }
}

// --- colors --------------------------------------------------------------------------------------

@Composable
private fun ColorsSection(state: EditorState) {
    SectionLabel("Colors (light / dark)")
    val colors = state.theme.colors
    if (colors.isEmpty()) MutedText("none")
    colors.forEach { (name, pair) ->
        TokenRow(
            name = name,
            onRename = { state.renameColor(name, it) },
            onRemove = { state.removeColor(name) },
        ) {
            HexField(pair.light, Modifier.weight(1f)) { state.setColor(name, pair.copy(light = it)) }
            HexField(pair.dark, Modifier.weight(1f)) { state.setColor(name, pair.copy(dark = it)) }
        }
    }
    AddRow("color name") { state.addColor(it) }
}

@Composable
private fun HexField(current: String, modifier: Modifier, onCommit: (String) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Swatch(hexToArgb(current))
        var text by remember { mutableStateOf(current) }
        var invalid by remember { mutableStateOf(false) }
        var focused by remember { mutableStateOf(false) }
        // Reflect an external change to the value only while NOT editing, so our own committed echo doesn't
        // rewrite a valid shorthand (00F) into its expansion (#0000FF) mid-typing (#188).
        LaunchedEffect(current, focused) {
            if (!focused && text != current) {
                text = current
                invalid = false
            }
        }
        FieldFrame(error = invalid, modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { input ->
                    text = input
                    val normalized = normalizeHex(input)
                    invalid = normalized == null
                    if (normalized != null) onCommit(normalized)
                },
                singleLine = true,
                textStyle = fieldTextStyle(),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
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

// --- typography ----------------------------------------------------------------------------------

@Composable
private fun TypographySection(state: EditorState) {
    SectionLabel("Typography (size / weight / line height)")
    val typography = state.theme.typography
    if (typography.isEmpty()) MutedText("none")
    typography.forEach { (name, token) ->
        TokenRow(
            name = name,
            onRename = { state.renameTypography(name, it) },
            onRemove = { state.removeTypography(name) },
        ) {
            IntField(token.fontSize, "sp", Modifier.weight(1f)) { state.setTypography(name, token.copy(fontSize = it)) }
            IntField(token.fontWeight, "w", Modifier.weight(1f)) {
                state.setTypography(name, token.copy(fontWeight = it))
            }
            IntField(token.lineHeight, "lh", Modifier.weight(1f)) {
                state.setTypography(name, token.copy(lineHeight = it))
            }
        }
    }
    AddRow("type name") { state.addTypography(it) }
}

// --- shapes --------------------------------------------------------------------------------------

@Composable
private fun ShapesSection(state: EditorState) {
    SectionLabel("Shapes (corner radius)")
    val shapes = state.theme.shapes
    if (shapes.isEmpty()) MutedText("none")
    shapes.forEach { (name, corner) ->
        TokenRow(
            name = name,
            onRename = { state.renameShape(name, it) },
            onRemove = { state.removeShape(name) },
        ) {
            IntField(corner, "dp", Modifier.weight(1f)) { state.setShape(name, it) }
        }
    }
    AddRow("shape name") { state.addShape(it) }
}

// --- spacing -------------------------------------------------------------------------------------

@Composable
private fun SpacingSection(state: EditorState) {
    SectionLabel("Spacing")
    val spacing = state.theme.spacing
    if (spacing.isEmpty()) MutedText("none")
    spacing.forEach { (name, dp) ->
        TokenRow(
            name = name,
            onRename = { state.renameSpacing(name, it) },
            onRemove = { state.removeSpacing(name) },
        ) {
            IntField(dp, "dp", Modifier.weight(1f)) { state.setSpacing(name, it) }
        }
    }
    AddRow("spacing name") { state.addSpacing(it) }
}

// --- shared token row / controls -----------------------------------------------------------------

/** One token: an editable name (rename on focus loss, H5), the group-specific value fields, remove. */
@Composable
private fun TokenRow(
    name: String,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
    fields: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NameField(name, Modifier.width(96.dp), onRename)
        fields()
        Text(
            "✕",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.clickable(onClick = onRemove).padding(horizontal = 4.dp),
        )
    }
}

/**
 * An editable token name. It commits a rename on any of the three ways a user finishes an edit — pressing
 * Enter, moving focus away, or closing the dialog — through one guarded [commit] (changed + non-blank; the
 * rename command itself no-ops if the source token is gone or the target name is taken). Committing only on
 * focus loss dropped Enter and dialog-close edits and made the adjacent ✕ look like it discarded the
 * rename (#182).
 */
@Composable
private fun NameField(current: String, modifier: Modifier, onRename: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    val commit = { if (text != current && text.isNotBlank()) onRename(text.trim()) }
    // Commit pending text when the field leaves composition (e.g. the dialog closes). Held via
    // rememberUpdatedState so onDispose reads the latest text/callback, and safe because the rename no-ops
    // when nothing changed — a removed token (source gone) commits to nothing.
    val latestCommit by rememberUpdatedState(commit)
    DisposableEffect(Unit) { onDispose { latestCommit() } }
    FieldFrame(
        modifier = modifier.onFocusChanged { focus -> if (!focus.isFocused) commit() },
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = fieldTextStyle(),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A small integer field with a unit suffix; commits only valid entries (blank/invalid are withheld, I8). */
@Composable
private fun IntField(current: Int, suffix: String, modifier: Modifier, onCommit: (Int) -> Unit) {
    var text by remember(current) { mutableStateOf(current.toString()) }
    var invalid by remember(current) { mutableStateOf(false) }
    FieldFrame(error = invalid, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = { input ->
                    text = input
                    when (val r = parseIntInput(input)) {
                        is NumberResult.Valid -> {
                            invalid = false
                            (r.value.literalText()?.toIntOrNull())?.let(onCommit)
                        }
                        else -> invalid = r == NumberResult.Invalid
                    }
                },
                singleLine = true,
                textStyle = fieldTextStyle(),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
            )
            Text(
                suffix,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The add-a-token row: a name field plus an "add" action, shared across all four sections. */
@Composable
private fun AddRow(placeholder: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FieldFrame(modifier = Modifier.width(160.dp)) {
            Box {
                if (text.isEmpty()) {
                    Text(
                        placeholder,
                        style = fieldTextStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = fieldTextStyle(),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text(
            "+ add",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable {
                    onAdd(text.trim())
                    text = ""
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun FieldFrame(modifier: Modifier = Modifier, error: Boolean = false, content: @Composable () -> Unit) {
    val base = modifier
        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
    val bordered = if (error) base.border(1.dp, MaterialTheme.colorScheme.error, MaterialTheme.shapes.small) else base
    Box(bordered.padding(horizontal = 8.dp, vertical = 6.dp)) { content() }
}

@Composable
private fun fieldTextStyle() = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface)

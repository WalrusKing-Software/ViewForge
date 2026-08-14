package viewforge.editor.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import viewforge.editor.state.CodePreviewService
import viewforge.editor.state.EditorState
import viewforge.editor.state.PreviewContent
import viewforge.editor.state.nodeAt
import viewforge.editor.state.previewContent
import viewforge.model.NodeId

/**
 * The live code-preview panel (FEATURES G3, #50): a **read-only** view of the generated Kotlin/Compose
 * for the active screen — or, while a component is open for in-place editing, that component (#69) —
 * refreshing as the document changes. It reaches the generator only through the Compose-free
 * [CodePreviewService] seam (ADR-013), so this module never names `packages/compose`.
 *
 * Selecting a node scrolls the panel to, and highlights, the code emitted for it (#51): the seam returns
 * a node→source-range map alongside the source, so this reads `state.selectedId` against it. The reverse
 * also holds (#103): tapping in the source selects the innermost node whose span encloses the caret, via
 * the same map ([nodeAt]). A header Copy action puts the shown source on the system clipboard (G8, #102);
 * the text also stays drag-selectable for copying by hand.
 */
@Composable
fun CodePreview(state: EditorState, service: CodePreviewService, modifier: Modifier = Modifier) {
    Column(modifier) {
        // The document is an immutable value replaced per command, so a change to its reference (or the
        // preview target) means an edit landed — regenerate then, not every frame (G3 "updates with the
        // document"). The target follows the open component when one is being edited, else the active
        // screen (#69). Generation is cheap (KotlinPoet), so keying on the whole document is fine.
        val content = remember(state.document, state.editingComponentId, state.activeScreen?.id) {
            previewContent(service, state.document, state.previewTarget)
        }
        // Copy the shown source to the system clipboard (G8, #102) — the same bytes the exporter emits,
        // for pasting into an existing project. Only offered when there is source (not a failure message).
        PanelHeader("Code", trailing = { if (content is PreviewContent.Source) CopyAction(content.code) })
        when (content) {
            // A generation failure is shown loudly in the error colour, never a blank panel (CLAUDE.md).
            is PreviewContent.Failure -> Text(
                text = content.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            is PreviewContent.Source -> SourceView(state, content)
        }
    }
}

/**
 * The generated source with the selected node's code highlighted and scrolled into view (#51). The span
 * comes from the seam's node→range map; selection and preview both follow the active edit surface, so the
 * id resolves against the shown source. Absent a selection (or a mapped range) the source shows plain.
 */
@Composable
private fun SourceView(state: EditorState, content: PreviewContent.Source) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val selectedRange = state.selectedId?.let { content.spans[it.value] }
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    val annotated = remember(content.code, selectedRange, highlight) {
        highlighted(content.code, selectedRange, highlight)
    }

    // Scroll the selected node's first line to the top once its span and the laid-out text are both known.
    LaunchedEffect(selectedRange, layout) {
        val laid = layout ?: return@LaunchedEffect
        val range = selectedRange ?: return@LaunchedEffect
        val line = laid.getLineForOffset(range.first.coerceIn(0, content.code.length))
        vScroll.animateScrollTo(laid.getLineTop(line).toInt())
    }

    // Soft-wrap (#115): when on, the source wraps to the panel width and the horizontal scroll is dropped
    // (it would do nothing); when off, lines stay full-width and scroll horizontally as before.
    val wrap = state.codePreviewWrap
    SelectionContainer(Modifier.fillMaxSize()) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            softWrap = wrap,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(vScroll)
                .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll))
                .padding(12.dp)
                // Tap the source to select the innermost node whose span covers the caret (#103, reverse of
                // #51). Keyed on the shown source so it always resolves against the current spans; a tap
                // outside every span (headers, imports, blank lines) selects nothing. Text remains
                // drag-selectable via the enclosing SelectionContainer.
                .pointerInput(content) {
                    detectTapGestures { pos ->
                        val laid = layout ?: return@detectTapGestures
                        content.spans.nodeAt(laid.getOffsetForPosition(pos))?.let { state.select(NodeId(it)) }
                    }
                },
        )
    }
}

/**
 * The code panel's Copy header action (G8, #102): copies [code] — the exact source shown, byte-identical
 * to the export — to the system clipboard, briefly confirming with a "Copied" label. Panel-layer only
 * (`LocalClipboardManager`), so the render/codegen layers stay pure.
 */
@Composable
private fun CopyAction(code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }
    Text(
        text = if (copied) "Copied" else "Copy",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable {
                clipboard.setText(AnnotatedString(code))
                copied = true
            }
            .padding(horizontal = 4.dp),
    )
}

/** [code] with a background [color] over [range] (the selected node's span), or plain when there is none. */
private fun highlighted(code: String, range: IntRange?, color: Color): AnnotatedString {
    if (range == null) return AnnotatedString(code)
    val start = range.first.coerceIn(0, code.length)
    val end = (range.last + 1).coerceIn(start, code.length)
    return buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(background = color), start, end)
    }
}

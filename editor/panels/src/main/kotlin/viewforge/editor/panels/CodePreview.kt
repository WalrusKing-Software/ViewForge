package viewforge.editor.panels

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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
 * the same map ([nodeAt]). Text stays selectable so a user can still copy by hand; copy-to-clipboard
 * remains a follow-up (G8, #102).
 */
@Composable
fun CodePreview(state: EditorState, service: CodePreviewService, modifier: Modifier = Modifier) {
    Column(modifier) {
        PanelHeader("Code")
        // The document is an immutable value replaced per command, so a change to its reference (or the
        // preview target) means an edit landed — regenerate then, not every frame (G3 "updates with the
        // document"). The target follows the open component when one is being edited, else the active
        // screen (#69). Generation is cheap (KotlinPoet), so keying on the whole document is fine.
        val content = remember(state.document, state.editingComponentId, state.activeScreen?.id) {
            previewContent(service, state.document, state.previewTarget)
        }
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

    SelectionContainer(Modifier.fillMaxSize()) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            softWrap = false,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(vScroll)
                .horizontalScroll(hScroll)
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

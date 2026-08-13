package viewforge.editor.panels

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import viewforge.editor.state.CodePreviewService
import viewforge.editor.state.EditorState
import viewforge.editor.state.PreviewContent
import viewforge.editor.state.previewContent

/**
 * The live code-preview panel (FEATURES G3, #50): a **read-only** view of the generated Kotlin/Compose
 * for the active screen, refreshing as the document changes. It reaches the generator only through the
 * Compose-free [CodePreviewService] seam (ADR-013), so this module never names `packages/compose`.
 *
 * Read-only in v1 (G3): the panel shows the whole active-screen composable. Selection-driven
 * scroll/highlight and copy-to-clipboard are tracked follow-ups (#51, G8). Text is selectable so a user
 * can still copy by hand.
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
            is PreviewContent.Source -> SelectionContainer(Modifier.fillMaxSize()) {
                Text(
                    text = content.code,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}

package viewforge.editor.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import viewforge.editor.canvas.CanvasRenderer
import viewforge.editor.canvas.EditorCanvas
import viewforge.editor.panels.Inspector
import viewforge.editor.panels.TreePanel
import viewforge.editor.state.EditorState

/**
 * The top of the editor UI (ARCHITECTURE §2): a title bar, the tree panel, the canvas, and the
 * inspector. The component palette lands at M4 (it's a mutation surface); its rail is a placeholder
 * until then.
 *
 * The shell wraps everything in its **own** `MaterialTheme` — the editor's chrome theme, kept
 * deliberately separate from the *project's* theme the canvas renders (FEATURES S3). Conflating the
 * two would let a project's colors restyle the editor.
 */
@Composable
fun EditorShell(state: EditorState, renderer: CanvasRenderer) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TitleBar(state)
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    TreePanel(state, Modifier.width(200.dp).fillMaxHeight())
                    VerticalDivider()
                    EditorCanvas(state, renderer, Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider()
                    Inspector(state, Modifier.width(240.dp).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun TitleBar(state: EditorState) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text = "ViewForge — ${state.project.name}",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

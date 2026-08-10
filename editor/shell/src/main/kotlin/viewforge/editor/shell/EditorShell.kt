package viewforge.editor.shell

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import viewforge.editor.canvas.CanvasRenderer
import viewforge.editor.canvas.EditorCanvas
import viewforge.editor.state.EditorState

/**
 * The top of the editor UI (ARCHITECTURE §2): a title bar, the canvas, and placeholder rails where
 * the palette, tree and inspector land at M3/M5.
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
                    PlaceholderRail("Palette", "M3", Modifier.width(160.dp))
                    EditorCanvas(state, renderer, Modifier.weight(1f).fillMaxHeight())
                    PlaceholderRail("Inspector", "M5", Modifier.width(220.dp))
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

/** A labelled empty panel standing in for a real panel that arrives in a later milestone. */
@Composable
private fun PlaceholderRail(name: String, milestone: String, modifier: Modifier) {
    Surface(modifier = modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                text = "$name\n(arrives at $milestone)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

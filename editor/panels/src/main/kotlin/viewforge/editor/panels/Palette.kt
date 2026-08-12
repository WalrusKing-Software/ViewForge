package viewforge.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import viewforge.editor.state.EditorState
import viewforge.editor.state.PaletteEntry

/**
 * The component palette (FEATURES P1a/P3a/P4a): a categorized, type-ahead-filtered list built
 * entirely from the framework package's catalog (`state.catalog.palette`). Clicking an entry inserts
 * a fresh node at the current insertion point via a command (`addFromPalette`) — adding a component
 * requires **no palette code**, so the list grows automatically as the package's catalog grows.
 *
 * Add-by-click into the current selection is M4's insertion gesture; drag-from-palette-to-canvas
 * (P2a) is a deliberate follow-up (see ADR-015).
 */
@Composable
fun Palette(state: EditorState, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    Column(modifier) {
        PanelHeader("Palette")
        SearchField(query, onChange = { query = it })
        val entries = state.catalog.palette.filter { it.matches(query) }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (entries.isEmpty()) {
                MutedText("No matches")
            } else {
                entries.groupBy { it.category }.forEach { (category, items) ->
                    SectionLabelInset(category)
                    items.forEach { entry ->
                        PaletteRow(state, entry)
                    }
                }
            }
        }
    }
}

/** Case-insensitive match against the label and category (type-ahead filtering, P3a). */
private fun PaletteEntry.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return label.contains(q, ignoreCase = true) || category.contains(q, ignoreCase = true)
}

/**
 * A palette entry: click to insert at the current selection (M4), or **drag onto the canvas** to drop
 * it at a position (P2a). The drag streams the pointer in window space (via the row's own
 * [LayoutCoordinates]) into [EditorState]; the canvas overlay resolves the target and the release
 * commits it. A press with no movement stays a click, so add-by-click is unchanged.
 */
@Composable
private fun PaletteRow(state: EditorState, entry: PaletteEntry) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Text(
        text = entry.label,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords = it }
            .pointerInput(entry.type) {
                detectDragGestures(
                    onDragStart = { local ->
                        val window = coords?.localToWindow(local) ?: return@detectDragGestures
                        state.beginPaletteDrag(entry.type)
                        state.updatePaletteDrag(window.x, window.y)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val window = coords?.localToWindow(change.position) ?: return@detectDragGestures
                        state.updatePaletteDrag(window.x, window.y)
                    },
                    onDragEnd = { state.dropPaletteDrag() },
                    onDragCancel = { state.cancelPaletteDrag() },
                )
            }
            .clickable(onClick = { state.addFromPalette(entry.type) })
            .padding(start = 20.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
    )
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (query.isEmpty()) {
            Text(
                "Search…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A category label indented to align with palette rows. */
@Composable
private fun SectionLabelInset(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
    )
}

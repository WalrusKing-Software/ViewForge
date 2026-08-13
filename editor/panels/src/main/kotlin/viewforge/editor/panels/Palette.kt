package viewforge.editor.panels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import viewforge.editor.state.EditorState
import viewforge.editor.state.PaletteEntry

/**
 * The component palette (FEATURES P1a/P3a/P4a/P6a): a categorized, type-ahead-filtered list built from
 * `state.palette` — the framework package's catalog plus this document's own user components (P6a).
 * Clicking an entry inserts a node at the current insertion point via a command (`addFromPalette`) —
 * adding a built-in requires **no palette code**, and user components appear automatically as they are
 * extracted, so the list grows on its own.
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
        val entries = state.palette.filter { it.matches(query) }
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
 *
 * A user-component entry additionally **double-clicks to open it for in-place editing** (#61) — the
 * canvas/tree switch to the component's own tree; the shell's breadcrumb returns to the screen. A
 * built-in has no definition, so double-click does nothing there.
 *
 * While a component is open for editing, an entry that would form a reference cycle — inserting a
 * component that (transitively) contains the one being edited — is **greyed out** and its click and
 * drag are refused, with a tooltip explaining why (#70). Double-click-to-open stays enabled: opening
 * such a component for editing is unrelated to inserting an instance of it here.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaletteRow(state: EditorState, entry: PaletteEntry) {
    val cyclic = state.paletteEntryWouldCycle(entry)
    if (cyclic) {
        TooltipArea(tooltip = { CycleTooltip(entry) }) { PaletteRowLabel(state, entry, disabled = true) }
    } else {
        PaletteRowLabel(state, entry, disabled = false)
    }
}

/** The label row itself: click to insert, drag to the canvas, double-click (a component) to open. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaletteRowLabel(state: EditorState, entry: PaletteEntry, disabled: Boolean) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Text(
        text = entry.label,
        style = MaterialTheme.typography.bodySmall,
        color = if (disabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else Color.Unspecified,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords = it }
            .pointerInput(entry) {
                detectDragGestures(
                    onDragStart = { local ->
                        // Read cycle-state fresh (it flips when a component is opened, without re-keying).
                        if (state.paletteEntryWouldCycle(entry)) return@detectDragGestures
                        val window = coords?.localToWindow(local) ?: return@detectDragGestures
                        state.beginPaletteDrag(entry)
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
            .combinedClickable(
                // A cycle-forming insert is a no-op in state too; onClick just stays quiet so the greyed
                // entry does nothing. Double-click still opens the component for editing (see above).
                onClick = { state.addFromPalette(entry) },
                onDoubleClick = entry.componentId?.let { id -> { state.openComponent(id) } },
            )
            .padding(start = 20.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
    )
}

/** The tooltip shown over a palette entry that can't be inserted because it would form a cycle (#70). */
@Composable
private fun CycleTooltip(entry: PaletteEntry) {
    Surface(shadowElevation = 4.dp, shape = MaterialTheme.shapes.small) {
        Text(
            "Can’t add “${entry.label}” here — it would contain the component you’re editing.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
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

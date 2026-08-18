package viewforge.editor.panels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
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
 * The component palette (FEATURES P1a/P3a/P4a/P5a/P6a): a categorized, type-ahead-filtered list built from
 * `state.palette` — the framework package's catalog plus this document's own user components (P6a).
 * Clicking an entry inserts a node at the current insertion point via a command (`addFromPalette`) —
 * adding a built-in requires **no palette code**, and user components appear automatically as they are
 * extracted, so the list grows on its own.
 *
 * When the search box is blank, two quick-access sections sit above the categories (P5a, #121): the user's
 * pinned **★ Favorites** (persisted) and the auto-tracked **Recent** entries (session-only). Every row —
 * in either section or the main list — carries a star that pins/unpins it, via [onToggleFavorite].
 *
 * Add-by-click into the current selection is M4's insertion gesture; drag-from-palette-to-canvas
 * (P2a) is a deliberate follow-up (see ADR-015).
 */
@Composable
fun Palette(
    state: EditorState,
    onToggleFavorite: (PaletteEntry) -> Unit,
    onInsert: (PaletteEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    Column(modifier) {
        PanelHeader("Palette")
        SearchField(query, onChange = { query = it })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // Quick access to pinned + recent components (P5a) — only with no active search, since a filtered
            // list already shows everything matching and the sections would just duplicate it.
            if (query.isBlank()) {
                QuickAccessSection("★ Favorites", state.favoriteEntries, state, onToggleFavorite, onInsert)
                QuickAccessSection("Recent", state.recentEntries, state, onToggleFavorite, onInsert)
            }
            val entries = state.palette.filter { it.matches(query) }
            if (entries.isEmpty()) {
                MutedText("No matches")
            } else {
                entries.groupBy { it.category }.forEach { (category, items) ->
                    SectionLabelInset(category)
                    items.forEach { entry ->
                        PaletteRow(state, entry, onToggleFavorite, onInsert)
                    }
                }
            }
        }
    }
}

/** A quick-access section (★ Favorites / Recent), shown only when it has entries so the palette stays compact (P5a). */
@Composable
private fun QuickAccessSection(
    title: String,
    entries: List<PaletteEntry>,
    state: EditorState,
    onToggleFavorite: (PaletteEntry) -> Unit,
    onInsert: (PaletteEntry) -> Unit,
) {
    if (entries.isEmpty()) return
    SectionLabelInset(title)
    entries.forEach { entry -> PaletteRow(state, entry, onToggleFavorite, onInsert) }
}

/** Case-insensitive match against the label and category (type-ahead filtering, P3a). */
private fun PaletteEntry.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return label.contains(q, ignoreCase = true) || category.contains(q, ignoreCase = true)
}

/**
 * A palette entry row: a label (click to insert / drag to the canvas / double-click a component to open) plus
 * a trailing star that pins or unpins it as a favorite (P5a, #121). A cycle-forming entry (#70) has its label
 * greyed with a tooltip and its insert/drag refused, but the star stays active — pinning is unrelated to
 * whether the entry can be inserted right here.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaletteRow(
    state: EditorState,
    entry: PaletteEntry,
    onToggleFavorite: (PaletteEntry) -> Unit,
    onInsert: (PaletteEntry) -> Unit,
) {
    val cyclic = state.paletteEntryWouldCycle(entry)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val labelModifier = Modifier.weight(1f)
        if (cyclic) {
            TooltipArea(tooltip = { CycleTooltip(entry) }) {
                PaletteRowLabel(state, entry, onInsert, disabled = true, modifier = labelModifier)
            }
        } else {
            PaletteRowLabel(state, entry, onInsert, disabled = false, modifier = labelModifier)
        }
        FavoriteStar(favorite = state.isFavorite(entry), onClick = { onToggleFavorite(entry) })
    }
}

/** The trailing pin: a filled star when favorited, an outline otherwise; click toggles without inserting (P5a). */
@Composable
private fun FavoriteStar(favorite: Boolean, onClick: () -> Unit) {
    Text(
        text = if (favorite) "★" else "☆",
        style = MaterialTheme.typography.bodySmall,
        color = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

/** The label itself: click to insert, drag to the canvas, double-click (a component) to open. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaletteRowLabel(
    state: EditorState,
    entry: PaletteEntry,
    onInsert: (PaletteEntry) -> Unit,
    disabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Text(
        text = entry.label,
        style = MaterialTheme.typography.bodySmall,
        color = if (disabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else Color.Unspecified,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .onGloballyPositioned { coords = it }
            .pointerInput(entry) {
                detectDragGestures(
                    onDragStart = { local ->
                        // A library entry inserts by copy-into-document (possibly via a name prompt), which
                        // has no drag surface this release (ADR-033) — click-to-insert only, so never start a
                        // drag for it. Read cycle-state fresh (it flips when a component is opened, no re-key).
                        if (entry.libraryId != null) return@detectDragGestures
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
                // Insert routes through [onInsert]: a built-in / document component adds directly, a library
                // entry copies into the document (prompting on a name collision). A cycle-forming or greyed
                // entry is a no-op in state too, so onClick just stays quiet. Double-click still opens a
                // *document* component for editing; a library entry has no id to open in place.
                onClick = { onInsert(entry) },
                onDoubleClick = entry.componentId?.let { id -> { state.openComponent(id) } },
            )
            .padding(start = 20.dp, top = 5.dp, bottom = 5.dp),
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

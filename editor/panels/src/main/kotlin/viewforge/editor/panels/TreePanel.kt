@file:OptIn(ExperimentalFoundationApi::class)

package viewforge.editor.panels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import viewforge.editor.state.EditorState
import viewforge.model.ChildAddress
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.allChildren
import viewforge.model.findById

/**
 * The layers/tree panel (FEATURES T1/T2/T3/T4): a hierarchical view of the document that mirrors the
 * IR exactly, syncs selection bidirectionally with the canvas, and is the *complete* selection and
 * mutation surface for nodes the canvas can't reach (zero-size, hidden — TECHNICAL_NOTES §3).
 *
 * M4 makes it an editing surface: **drag a row to reorder or reparent** (T2) with a live drop
 * indicator and the same validation the canvas would use (`EditorState.canDrop` → `moveNode`);
 * **double-click to rename** (T3); and per-row **lock/hide** toggles (T4). Every change goes through
 * a command, so all of it participates in undo/redo. Delete and duplicate act on the selection from
 * the shell toolbar / keyboard.
 *
 * The tree is rendered from a *flattened* row list (respecting expand/collapse and slots) so drag
 * hit-testing is a simple linear scan of recorded row bounds rather than recursive geometry.
 */
@Composable
fun TreePanel(state: EditorState, modifier: Modifier = Modifier) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val drag = remember(state) { TreeDragState(state) }
    var renamingId by remember { mutableStateOf<NodeId?>(null) }

    // F2 (or any shell-level rename request) drops the selected node's row into inline edit (T3). The
    // request is set from the shell's key handler so it works whatever surface has focus; consume it here.
    LaunchedEffect(state.renameRequest) {
        state.renameRequest?.let { id ->
            renamingId = id
            state.clearRenameRequest()
        }
    }

    Column(modifier) {
        PanelHeader("Layers")
        val root = state.activeEditRoot
        if (root == null) {
            MutedText("No screen")
        } else {
            val rows = remember(root, expanded.toMap()) {
                flatten(root, depth = 0, ownAddress = null, expanded = expanded)
            }
            // Keep the drag controller's view of node addresses in sync with what's on screen.
            drag.items = rows.filterIsInstance<NodeRowItem>()
                .associate { it.node.id.value to NodeItemInfo(it.node, it.ownAddress) }

            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { item ->
                    when (item) {
                        is SlotRowItem -> SlotHeader(item.name, item.depth)
                        is NodeRowItem -> NodeRow(
                            state = state,
                            drag = drag,
                            item = item,
                            expanded = expanded[item.node.id.value] ?: true,
                            renaming = renamingId == item.node.id,
                            onToggleExpand = { expanded[item.node.id.value] = !(expanded[item.node.id.value] ?: true) },
                            onStartRename = { renamingId = item.node.id },
                            onEndRename = { renamingId = null },
                        )
                    }
                }
            }
        }
    }
}

// --- flattened model -----------------------------------------------------------------------------

private sealed interface TreeRow {
    val depth: Int
}

/** A selectable/draggable node row, carrying its own address for before/after drop resolution. */
private data class NodeRowItem(val node: Node, override val depth: Int, val ownAddress: ChildAddress?) : TreeRow

/** A non-interactive header separating a parent's named slot region. */
private data class SlotRowItem(val name: String, override val depth: Int) : TreeRow

private fun flatten(node: Node, depth: Int, ownAddress: ChildAddress?, expanded: Map<String, Boolean>): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    out += NodeRowItem(node, depth, ownAddress)
    val isExpanded = expanded[node.id.value] ?: true
    if (isExpanded && node.allChildren().isNotEmpty()) {
        node.children.forEachIndexed { i, child ->
            out += flatten(child, depth + 1, ChildAddress(node.id, null, i), expanded)
        }
        node.slots.forEach { (slot, list) ->
            out += SlotRowItem(slot, depth + 1)
            list.forEachIndexed { i, child ->
                out += flatten(child, depth + 2, ChildAddress(node.id, slot, i), expanded)
            }
        }
    }
    return out
}

// --- drag-and-drop controller --------------------------------------------------------------------

private enum class DropZone { Before, Into, After }

private data class NodeItemInfo(val node: Node, val ownAddress: ChildAddress?)

/**
 * Holds transient drag state and resolves a pointer position to a validated drop target. Bounds are
 * recorded per row in window space (like the canvas spatial index); a drag is hit-tested by a linear
 * scan and split into before/into/after zones. Resolution excludes the dragged node from the target
 * list so an index is always the *post-removal* position [MoveNode][viewforge.command.MoveNode]
 * expects — which makes same-parent reorders land exactly where the indicator shows.
 */
private class TreeDragState(private val state: EditorState) {
    var draggingId by mutableStateOf<NodeId?>(null)
        private set
    var dropTargetKey by mutableStateOf<String?>(null)
        private set
    var dropZone by mutableStateOf<DropZone?>(null)
        private set
    var dropValid by mutableStateOf(false)
        private set

    private var dropAddress: ChildAddress? = null
    val bounds = mutableStateMapOf<String, Rect>()
    val coords = HashMap<String, LayoutCoordinates>()
    var items: Map<String, NodeItemInfo> = emptyMap()

    fun begin(id: NodeId) {
        draggingId = id
    }

    fun update(windowY: Float) {
        val root = state.activeEditRoot
        val dragged = draggingId
        if (root == null || dragged == null) return clearTarget()
        val hit = bounds.entries.firstOrNull { windowY >= it.value.top && windowY < it.value.bottom }
        val info = hit?.let { items[it.key] }
        if (hit == null || info == null || info.node.id == dragged) return clearTarget()

        val rect = hit.value
        val rel = ((windowY - rect.top) / rect.height).coerceIn(0f, 1f)
        val container = state.catalog.isContainer(info.node.type)
        val zone = when {
            rel < BEFORE_ZONE -> DropZone.Before
            rel > AFTER_ZONE -> DropZone.After
            container -> DropZone.Into
            rel < 0.5f -> DropZone.Before
            else -> DropZone.After
        }
        val address = resolve(root, dragged, info, zone)
        val valid = address != null && state.canDrop(dragged, address)
        dropTargetKey = hit.key
        dropZone = zone
        dropValid = valid
        dropAddress = if (valid) address else null
    }

    fun commit() {
        val id = draggingId
        val addr = dropAddress
        if (dropValid && id != null && addr != null) state.moveNode(id, addr)
        reset()
    }

    fun reset() {
        draggingId = null
        clearTarget()
    }

    private fun clearTarget() {
        dropTargetKey = null
        dropZone = null
        dropValid = false
        dropAddress = null
    }

    private fun resolve(root: Node, draggedId: NodeId, info: NodeItemInfo, zone: DropZone): ChildAddress? {
        if (zone == DropZone.Into) return appendInto(info.node, draggedId)
        val own = info.ownAddress ?: return null // root has no before/after
        val parent = root.findById(own.parentId) ?: return null
        val region = if (own.slot == null) parent.children else parent.slots[own.slot].orEmpty()
        val filtered = region.filter { it.id != draggedId }
        val idx = filtered.indexOfFirst { it.id == info.node.id }
        if (idx < 0) return null
        return ChildAddress(own.parentId, own.slot, if (zone == DropZone.Before) idx else idx + 1)
    }

    private fun appendInto(target: Node, draggedId: NodeId): ChildAddress? {
        if (state.catalog.acceptsChildren(target.type)) {
            return ChildAddress(target.id, null, target.children.count { it.id != draggedId })
        }
        val slot = state.catalog.slotsOf(target.type).firstOrNull() ?: return null
        return ChildAddress(target.id, slot, target.slots[slot].orEmpty().count { it.id != draggedId })
    }

    companion object {
        private const val BEFORE_ZONE = 0.30f
        private const val AFTER_ZONE = 0.70f
    }
}

// --- rows ----------------------------------------------------------------------------------------

@Composable
private fun NodeRow(
    state: EditorState,
    drag: TreeDragState,
    item: NodeRowItem,
    expanded: Boolean,
    renaming: Boolean,
    onToggleExpand: () -> Unit,
    onStartRename: () -> Unit,
    onEndRename: () -> Unit,
) {
    val node = item.node
    val key = node.id.value
    val selected = state.selectedId == node.id
    val hasChildren = node.allChildren().isNotEmpty()
    val isDragged = drag.draggingId == node.id

    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .drawBehind { drawDropIndicator(drag, key) }
            .alpha(if (isDragged) 0.4f else 1f)
            .onGloballyPositioned {
                drag.bounds[key] = it.boundsInWindow()
                drag.coords[key] = it
            }
            .pointerInput(node.id) {
                detectDragGestures(
                    onDragStart = { drag.begin(node.id) },
                    onDrag = { change, _ ->
                        change.consume()
                        drag.coords[key]?.let { c -> drag.update(c.localToWindow(change.position).y) }
                    },
                    onDragEnd = { drag.commit() },
                    onDragCancel = { drag.reset() },
                )
            }
            .combinedClickable(
                onClick = { state.select(node.id) },
                onDoubleClick = onStartRename,
            )
            .padding(start = (8 + item.depth * INDENT).dp, top = 3.dp, bottom = 3.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (hasChildren) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable(onClick = onToggleExpand),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            if (renaming) {
                RenameField(
                    initial = node.name ?: "",
                    onCommit = {
                        state.renameNode(node.id, it)
                        onEndRename()
                    },
                    onCancel = onEndRename,
                )
            } else {
                Text(
                    text = displayLabel(node),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = when {
                        selected -> MaterialTheme.colorScheme.onPrimaryContainer
                        node.hidden -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }

        FlagToggle("H", active = node.hidden, onClick = { state.toggleHidden(node.id) })
        FlagToggle("L", active = node.locked, onClick = { state.toggleLocked(node.id) })
    }
}

/** A compact per-row flag toggle (hide/lock, T4). Emphasized when set, faint when not. */
@Composable
private fun FlagToggle(glyph: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = glyph,
        style = MaterialTheme.typography.labelSmall,
        color = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        },
        modifier = Modifier
            .width(16.dp)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun RenameField(initial: String, onCommit: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var everFocused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { st ->
                if (st.isFocused) {
                    everFocused = true
                } else if (everFocused) {
                    onCommit(text)
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter -> {
                        onCommit(text)
                        true
                    }
                    Key.Escape -> {
                        onCancel()
                        true
                    }
                    else -> false
                }
            },
    )
}

@Composable
private fun SlotHeader(name: String, depth: Int) {
    Text(
        text = "$name:",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = (8 + depth * INDENT).dp, top = 3.dp, bottom = 3.dp),
    )
}

/** Draw the before/after line or into-outline for the active drop target, green if valid else red. */
private fun DrawScope.drawDropIndicator(drag: TreeDragState, key: String) {
    if (drag.dropTargetKey != key || drag.draggingId == null) return
    val color = if (drag.dropValid) DROP_OK else DROP_BAD
    val bottom = size.height
    when (drag.dropZone) {
        DropZone.Before -> drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = DROP_STROKE)
        DropZone.After -> drawLine(color, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = DROP_STROKE)
        DropZone.Into -> drawRect(color, style = Stroke(width = DROP_STROKE))
        null -> {}
    }
}

private const val INDENT = 14
private const val DROP_STROKE = 2f
private val DROP_OK = Color(0xFF43A047)
private val DROP_BAD = Color(0xFFB00020)

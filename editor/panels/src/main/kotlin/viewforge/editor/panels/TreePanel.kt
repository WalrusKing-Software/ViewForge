@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)

package viewforge.editor.panels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
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
 * With the tree focused (a row click focuses it), the keyboard drives it (T5): Up/Down move the primary
 * selection along the visible order (skipping locked rows), Left/Right collapse/expand or step out/in, and
 * Enter renames the selected row. Delete stays with the shell's global handler.
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

    // Type-ahead search/filter (T6, #122). Panel-local, ephemeral view state — it filters the displayed
    // rows only, so it lives here rather than on EditorState.
    var query by remember { mutableStateOf("") }

    Column(modifier) {
        PanelHeader("Layers")
        val root = state.activeEditRoot
        if (root == null) {
            MutedText("No screen")
        } else {
            TreeSearchField(query = query, onQueryChange = { query = it })
            // Filter to matching nodes and their ancestors (null = no query = full tree). When filtering,
            // `flatten` force-expands along the kept paths so a match under a collapsed ancestor still shows.
            val keep = remember(root, query) { searchKeepSet(root, query) }
            val rows = remember(root, expanded.toMap(), keep) {
                if (keep != null && keep.isEmpty()) emptyList() else flatten(root, 0, null, expanded, keep)
            }
            if (rows.isEmpty()) {
                MutedText("No matches")
                return@Column
            }
            val nodeItems = rows.filterIsInstance<NodeRowItem>()
            // Keep the drag controller's view of node addresses in sync with what's on screen.
            drag.items = nodeItems.associate { it.node.id.value to NodeItemInfo(it.node, it.ownAddress) }

            // Palette→tree drop (#164): while a palette drag is live, resolve the window-space pointer the
            // palette streams against the row bounds and publish the address, so the palette's release
            // commits an AddNode here (the same flow the canvas uses). Runs after layout via SideEffect, so
            // the row bounds recorded by onGloballyPositioned are current. Clearing when the drag leaves the
            // tree lets the canvas' resolution take over.
            val paletteType = state.paletteDragType
            val pointerX = state.paletteDragX
            val pointerY = state.paletteDragY
            if (paletteType != null && pointerX != null && pointerY != null) {
                SideEffect { drag.resolvePalette(pointerX, pointerY) }
            } else if (drag.paletteActive) {
                SideEffect { drag.clearPalette() }
            }
            // The visible top-to-bottom order a shift-click range extends along (C10).
            val visibleOrder = remember(nodeItems) { nodeItems.map { it.node.id } }

            // T5 keyboard nav: the navigable order skips locked rows (they can't be selected); plus the
            // parent and has-children lookups the Left/Right actions consult.
            val navigable = remember(nodeItems) { nodeItems.filterNot { it.node.locked }.map { it.node.id.value } }
            val parentOf = remember(nodeItems) { nodeItems.associate { it.node.id.value to it.ownAddress?.parentId } }
            val childful = remember(nodeItems) {
                nodeItems.filter { it.node.allChildren().isNotEmpty() }.map { it.node.id.value }.toSet()
            }
            val treeFocus = remember { FocusRequester() }

            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .focusRequester(treeFocus)
                    .focusable()
                    // Bubbling (onKeyEvent, not preview) so a focused rename field consumes Enter/Escape
                    // first; arrows and Enter drive traversal/rename only when the tree holds focus (T5).
                    .onKeyEvent { event -> handleTreeKey(event, state, navigable, parentOf, childful, expanded) },
            ) {
                rows.forEach { item ->
                    when (item) {
                        is SlotRowItem -> SlotHeader(item.name, item.depth)
                        is NodeRowItem -> NodeRow(
                            state = state,
                            drag = drag,
                            item = item,
                            visibleOrder = visibleOrder,
                            expanded = expanded[item.node.id.value] ?: true,
                            renaming = renamingId == item.node.id,
                            onToggleExpand = { expanded[item.node.id.value] = !(expanded[item.node.id.value] ?: true) },
                            onStartRename = { renamingId = item.node.id },
                            onEndRename = { renamingId = null },
                            onFocusTree = { treeFocus.requestFocus() },
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

/**
 * Flatten [node] into display rows. When [keep] is non-null (a search is active, T6/#122) only nodes in it
 * are emitted, and the walk force-expands (ignoring collapse state) so a match under a collapsed ancestor
 * still shows; a slot header appears only if the slot has a kept child. When [keep] is null the collapse
 * state is honoured and every slot header shows, as before. Child indices stay the *original* positions so
 * a filtered row still carries the right [ChildAddress] for drop resolution.
 */
private fun flatten(
    node: Node,
    depth: Int,
    ownAddress: ChildAddress?,
    expanded: Map<String, Boolean>,
    keep: Set<String>?,
): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    out += NodeRowItem(node, depth, ownAddress)
    val descend = if (keep != null) true else (expanded[node.id.value] ?: true)
    if (descend && node.allChildren().isNotEmpty()) {
        node.children.forEachIndexed { i, child ->
            if (keep == null || child.id.value in keep) {
                out += flatten(child, depth + 1, ChildAddress(node.id, null, i), expanded, keep)
            }
        }
        node.slots.forEach { (slot, list) ->
            val shown = list.withIndex().filter { keep == null || it.value.id.value in keep }
            if (keep == null || shown.isNotEmpty()) {
                out += SlotRowItem(slot, depth + 1)
                shown.forEach { (i, child) ->
                    out += flatten(child, depth + 2, ChildAddress(node.id, slot, i), expanded, keep)
                }
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

    /** A palette→tree drop is in flight (#164): drives the drop indicator when no *row* is being dragged. */
    var paletteActive by mutableStateOf(false)
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
        // A row drag stays inside the panel, so a y-only scan suffices (unlike the palette drag below).
        val hit = bounds.entries.firstOrNull { windowY >= it.value.top && windowY < it.value.bottom }
        val info = hit?.let { items[it.key] }
        if (hit == null || info == null || info.node.id == dragged) return clearTarget()

        val zone = zoneFor(hit.value, windowY, info.node)
        val address = resolve(root, dragged, info, zone)
        val valid = address != null && state.canDrop(dragged, address)
        dropTargetKey = hit.key
        dropZone = zone
        dropValid = valid
        dropAddress = if (valid) address else null
    }

    /**
     * Resolve a live palette drag (#164) at the window-space pointer and publish the address so the
     * palette's release commits an `AddNode` here. Unlike [update] this hit-tests **both** axes — the
     * pointer may be over the canvas at the same y as a row — and excludes no node (the dragged node
     * doesn't exist yet). Validity is just "a legal address"; a cycle-forming component can't start a
     * drag (#70) and [EditorState.dropPaletteDrag] guards it again, so there is nothing more to check.
     */
    fun resolvePalette(windowX: Float, windowY: Float) {
        paletteActive = true
        val root = state.activeEditRoot
        val hit = root?.let {
            bounds.entries.firstOrNull { e ->
                windowX >= e.value.left && windowX < e.value.right && windowY >= e.value.top && windowY < e.value.bottom
            }
        }
        val info = hit?.let { items[it.key] }
        if (root == null || hit == null || info == null) {
            clearTarget()
            state.resolveTreePaletteDrop(null)
            return
        }
        val zone = zoneFor(hit.value, windowY, info.node)
        val address = resolve(root, null, info, zone)
        dropTargetKey = hit.key
        dropZone = zone
        dropValid = address != null
        dropAddress = address
        state.resolveTreePaletteDrop(address)
    }

    /** End the palette-drop visuals when the drag leaves the tree or finishes. */
    fun clearPalette() {
        paletteActive = false
        clearTarget()
    }

    /** Split a row into a before/into/after drop zone by where [windowY] falls within its [rect]. */
    private fun zoneFor(rect: Rect, windowY: Float, node: Node): DropZone {
        val rel = ((windowY - rect.top) / rect.height).coerceIn(0f, 1f)
        return when {
            rel < BEFORE_ZONE -> DropZone.Before
            rel > AFTER_ZONE -> DropZone.After
            state.catalog.isContainer(node.type) -> DropZone.Into
            rel < 0.5f -> DropZone.Before
            else -> DropZone.After
        }
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

    // [draggedId] is null for a palette drag (nothing to exclude); non-null for a row reorder/reparent.
    private fun resolve(root: Node, draggedId: NodeId?, info: NodeItemInfo, zone: DropZone): ChildAddress? {
        if (zone == DropZone.Into) return appendInto(info.node, draggedId)
        val own = info.ownAddress ?: return null // root has no before/after
        val parent = root.findById(own.parentId) ?: return null
        val region = if (own.slot == null) parent.children else parent.slots[own.slot].orEmpty()
        val filtered = region.filter { it.id != draggedId }
        val idx = filtered.indexOfFirst { it.id == info.node.id }
        if (idx < 0) return null
        return ChildAddress(own.parentId, own.slot, if (zone == DropZone.Before) idx else idx + 1)
    }

    private fun appendInto(target: Node, draggedId: NodeId?): ChildAddress? {
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

// --- keyboard navigation (T5) --------------------------------------------------------------------

/**
 * Resolve a key press over the focused tree (T5): Up/Down move the primary selection along [navigable]
 * (locked rows skipped); Left/Right collapse/expand or step out/in via [horizontalTreeAction]; Enter
 * renames the selected row through the existing request. Returns true when the key was handled (so it is
 * consumed and does not bubble to the shell). Delete is intentionally left to the shell's global handler.
 */
private fun handleTreeKey(
    event: KeyEvent,
    state: EditorState,
    navigable: List<String>,
    parentOf: Map<String, NodeId?>,
    childful: Set<String>,
    expanded: MutableMap<String, Boolean>,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val current = state.selectedId?.value
    return when (event.key) {
        Key.DirectionDown -> {
            nextNavigable(navigable, current, +1)?.let { state.select(NodeId(it)) }
            true
        }
        Key.DirectionUp -> {
            nextNavigable(navigable, current, -1)?.let { state.select(NodeId(it)) }
            true
        }
        Key.DirectionRight, Key.DirectionLeft -> {
            if (current != null) {
                applyHorizontal(state, current, parentOf, childful, expanded, right = event.key == Key.DirectionRight)
            }
            true
        }
        Key.Enter -> {
            if (current != null) state.requestRenameSelected()
            true
        }
        else -> false
    }
}

/** Apply the Left/Right outcome for [current]: expand/collapse the row or move selection to parent/child. */
private fun applyHorizontal(
    state: EditorState,
    current: String,
    parentOf: Map<String, NodeId?>,
    childful: Set<String>,
    expanded: MutableMap<String, Boolean>,
    right: Boolean,
) {
    val action = horizontalTreeAction(
        hasChildren = current in childful,
        expanded = expanded[current] ?: true,
        right = right,
    )
    when (action) {
        TreeKeyAction.Expand -> expanded[current] = true
        TreeKeyAction.Collapse -> expanded[current] = false
        TreeKeyAction.ToParent -> parentOf[current]?.let { state.select(it) }
        TreeKeyAction.ToFirstChild ->
            parentOf.entries.firstOrNull { it.value?.value == current }?.let { state.select(NodeId(it.key)) }
        TreeKeyAction.None -> Unit
    }
}

// --- rows ----------------------------------------------------------------------------------------

@Composable
private fun NodeRow(
    state: EditorState,
    drag: TreeDragState,
    item: NodeRowItem,
    visibleOrder: List<NodeId>,
    expanded: Boolean,
    renaming: Boolean,
    onToggleExpand: () -> Unit,
    onStartRename: () -> Unit,
    onEndRename: () -> Unit,
    onFocusTree: () -> Unit,
) {
    val node = item.node
    val key = node.id.value
    val selected = state.isSelected(node.id)
    val isPrimary = state.selectedId == node.id
    val hasChildren = node.allChildren().isNotEmpty()
    val isDragged = drag.draggingId == node.id
    val windowInfo = LocalWindowInfo.current

    // The primary selection gets the full container tint; the rest of a multi-selection a fainter one (C10).
    val background = when {
        isPrimary -> MaterialTheme.colorScheme.primaryContainer
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else -> Color.Transparent
    }

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
            // Right-click opens the node context menu (#160). Reuses the per-row window-space coordinates
            // the drag capture already records, so the shell can position the menu under the pointer.
            .pointerInput(node.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            onFocusTree()
                            val w = drag.coords[key]?.localToWindow(event.changes.first().position)
                            if (w != null) state.requestContextMenu(node.id, w.x, w.y)
                        }
                    }
                }
            }
            .combinedClickable(
                onClick = {
                    // Clicking a row focuses the tree so the arrow keys traverse from here on (T5).
                    onFocusTree()
                    // Ctrl/Cmd-click toggles a node in/out of the selection; Shift-click extends a range
                    // from the anchor along the visible order; a plain click selects just this node (C10).
                    val mods = windowInfo.keyboardModifiers
                    when {
                        mods.isShiftPressed -> state.extendSelectionTo(node.id, visibleOrder)
                        mods.isCtrlPressed || mods.isMetaPressed -> state.toggleSelection(node.id)
                        else -> state.select(node.id)
                    }
                },
                // Double-clicking an instance enters its component (#68); any other node starts a rename —
                // except a locked node, which is protected (T4) and can't be renamed.
                onDoubleClick = { if (!state.openInstanceComponent(node) && !node.locked) onStartRename() },
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A leading lock glyph marks a locked row at a glance (T4), beyond the "L" toggle's tint.
                    if (node.locked) {
                        Text(
                            text = "🔒",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
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

/** The type-ahead search box at the top of the layers panel (T6, #122). Bare like [RenameField]. */
@Composable
private fun TreeSearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Search",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onQueryChange("") }.padding(start = 4.dp),
            )
        }
    }
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
    // Draw for a row drag (draggingId set) or a palette→tree drag (#164, paletteActive) — never when idle.
    if (drag.dropTargetKey != key || (drag.draggingId == null && !drag.paletteActive)) return
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

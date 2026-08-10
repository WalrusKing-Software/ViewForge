package viewforge.editor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import viewforge.command.AddNode
import viewforge.command.Command
import viewforge.command.History
import viewforge.command.MoveNode
import viewforge.command.RemoveNode
import viewforge.command.RenameNode
import viewforge.command.SetNodeFlags
import viewforge.model.ChildAddress
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.Screen
import viewforge.model.findById
import viewforge.model.locate
import viewforge.model.subtreeContains
import viewforge.model.withFreshIds

/**
 * The editor's document session, exposed as Compose state so the canvas, tree, and inspector
 * recompose automatically (ARCHITECTURE §5).
 *
 * From M4 the document is **mutable, but only through commands** (CLAUDE.md rule 3). Every edit runs
 * `history.execute`, which applies a [Command] and records its inverse; [undo]/[redo] walk that
 * history. There is no direct-mutation path — the high-level operations here (add, delete, move,
 * rename, duplicate, clipboard) all translate a user intent into a command.
 *
 * Framework knowledge (which components exist, which are containers) comes from the injected
 * [catalog], the editor's Compose-free seam onto the package (ADR-013). Nothing here names Compose.
 */
class EditorState(initial: Project, val catalog: ComponentCatalog) {
    /** The live document — the single source of truth the UI renders (ARCHITECTURE §1). */
    var document: Project by mutableStateOf(initial)
        private set

    private val history = History()

    /**
     * The clipboard for copy/cut/paste (D5). Transient view state, never part of the document; holds
     * an immutable node snapshot that paste clones with fresh ids.
     */
    var clipboard: Node? by mutableStateOf(null)
        private set

    /** Which screen the canvas shows. */
    var activeScreenId: String? by mutableStateOf(initial.screens.firstOrNull()?.id)

    val activeScreen: Screen?
        get() = document.screens.firstOrNull { it.id == activeScreenId } ?: document.screens.firstOrNull()

    /**
     * The selected node's id, or null. Shared, observable state so canvas and tree stay in sync (T1).
     * Selection is transient view state — it lives here, never in the IR.
     */
    var selectedId: NodeId? by mutableStateOf(null)
        private set

    /** The selected [Node] resolved against the active screen, or null. */
    val selectedNode: Node?
        get() = selectedId?.let { activeScreen?.root?.findById(it) }

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
    val undoLabel: String? get() = history.undoLabel
    val redoLabel: String? get() = history.redoLabel
    val canPaste: Boolean get() = clipboard != null

    /**
     * Select a node by id, or null to clear. A **locked** node cannot be selected (T4); the request is
     * simply ignored so the current selection stands.
     */
    fun select(id: NodeId?) {
        if (id == null) {
            selectedId = null
            return
        }
        if (activeScreen?.root?.findById(id)?.locked == true) return
        selectedId = id
    }

    // --- history ----------------------------------------------------------------------------------

    /**
     * Run [command] through history and update the document. [selectAfter] is the node to leave
     * selected (defaulting to the current selection); it is honored only if it still exists afterward,
     * otherwise selection clears — so deleting the selected node never leaves a dangling selection.
     */
    fun execute(command: Command, selectAfter: NodeId? = selectedId) {
        document = history.execute(command, document)
        reconcileSelection(selectAfter)
    }

    fun undo() {
        if (!history.canUndo) return
        document = history.undo(document)
        reconcileSelection(selectedId)
    }

    fun redo() {
        if (!history.canRedo) return
        document = history.redo(document)
        reconcileSelection(selectedId)
    }

    private fun reconcileSelection(desired: NodeId?) {
        selectedId = desired?.takeIf { activeScreen?.root?.findById(it) != null }
    }

    // --- high-level operations --------------------------------------------------------------------

    /** Add a fresh [type] node from the palette at the current insertion point, and select it. */
    fun addFromPalette(type: String) {
        val screen = activeScreen ?: return
        val address = insertionAddress() ?: return
        val node = catalog.newNode(type)
        execute(AddNode(screen.id, address, node), selectAfter = node.id)
    }

    /** Delete the selected node (never the root), leaving its parent selected. */
    fun deleteSelected() {
        val screen = activeScreen ?: return
        val id = selectedId ?: return
        if (id == screen.root.id) return
        val parentId = screen.root.locate(id)?.parentId
        execute(RemoveNode(screen.id, id), selectAfter = parentId)
    }

    /** Duplicate the selected subtree (fresh ids) as its next sibling, and select the clone. */
    fun duplicateSelected() {
        val screen = activeScreen ?: return
        val node = selectedNode ?: return
        val addr = screen.root.locate(node.id) ?: return // root has no sibling slot
        val clone = node.withFreshIds()
        execute(AddNode(screen.id, addr.copy(index = addr.index + 1), clone), selectAfter = clone.id)
    }

    /** Set (or clear) a node's name (T3). */
    fun renameNode(id: NodeId, name: String?) {
        val screen = activeScreen ?: return
        execute(RenameNode(screen.id, id, name), selectAfter = selectedId)
    }

    /** Toggle a node's hidden flag (removes it from render and codegen, DATA_MODEL §5). */
    fun toggleHidden(id: NodeId) {
        val screen = activeScreen ?: return
        val node = screen.root.findById(id) ?: return
        execute(SetNodeFlags(screen.id, id, hidden = !node.hidden), selectAfter = selectedId)
    }

    /** Toggle a node's locked flag; locking the current selection also clears it (locked ⇒ unselectable). */
    fun toggleLocked(id: NodeId) {
        val screen = activeScreen ?: return
        val node = screen.root.findById(id) ?: return
        val nowLocked = !node.locked
        val keep = if (nowLocked && selectedId == id) null else selectedId
        execute(SetNodeFlags(screen.id, id, locked = nowLocked), selectAfter = keep)
    }

    /** Copy the selection to the clipboard (D5). */
    fun copySelected() {
        clipboard = selectedNode
    }

    /** Cut the selection: copy it, then delete it. */
    fun cut() {
        val node = selectedNode ?: return
        clipboard = node
        deleteSelected()
    }

    /** Paste a fresh-id clone of the clipboard at the current insertion point (D5: targets selection). */
    fun paste() {
        val screen = activeScreen ?: return
        val template = clipboard ?: return
        val address = insertionAddress() ?: return
        val clone = template.withFreshIds()
        execute(AddNode(screen.id, address, clone), selectAfter = clone.id)
    }

    /** Move [id] to [target] (reorder or reparent), if the drop is legal; keeps the node selected. */
    fun moveNode(id: NodeId, target: ChildAddress) {
        val screen = activeScreen ?: return
        if (!canDrop(id, target)) return
        execute(MoveNode(screen.id, id, target), selectAfter = id)
    }

    // --- drop validation --------------------------------------------------------------------------

    /**
     * Whether dragging [dragId] onto [target] is a legal drop (C7/T2 rules): the target parent must
     * accept that region (default children or the named slot, per [catalog]), and the target parent
     * must not be inside the dragged node's own subtree — you cannot reparent a node into itself or a
     * descendant.
     */
    fun canDrop(dragId: NodeId, target: ChildAddress): Boolean {
        val root = activeScreen?.root ?: return false
        val dragged = root.findById(dragId) ?: return false
        val parent = root.findById(target.parentId) ?: return false
        if (dragged.subtreeContains(target.parentId)) return false
        return if (target.slot == null) {
            catalog.acceptsChildren(parent.type)
        } else {
            catalog.slotsOf(parent.type).contains(target.slot)
        }
    }

    // --- placement helpers ------------------------------------------------------------------------

    /**
     * Where a new node should land relative to the current selection: appended inside the selection if
     * it is a container, otherwise as the selection's next sibling; with no selection, appended to the
     * root. Null only if there is no active screen.
     */
    private fun insertionAddress(): ChildAddress? {
        val root = activeScreen?.root ?: return null
        val selected = selectedNode ?: return appendAddress(root)
        appendAddress(selected)?.let { return it }
        // A leaf selection: insert right after it, in the same region.
        val addr = root.locate(selected.id) ?: return appendAddress(root)
        return addr.copy(index = addr.index + 1)
    }

    /** Append position inside [node]: its default region if it accepts children, else its first slot. */
    private fun appendAddress(node: Node): ChildAddress? {
        if (catalog.acceptsChildren(node.type)) return ChildAddress(node.id, null, node.children.size)
        val slot = catalog.slotsOf(node.type).firstOrNull() ?: return null
        return ChildAddress(node.id, slot, node.slots[slot]?.size ?: 0)
    }
}

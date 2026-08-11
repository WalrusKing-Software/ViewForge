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
import viewforge.command.RenameThemeToken
import viewforge.command.SetModifierArg
import viewforge.command.SetModifiers
import viewforge.command.SetNodeFlags
import viewforge.command.SetProp
import viewforge.command.SetTheme
import viewforge.model.ChildAddress
import viewforge.model.ColorPair
import viewforge.model.ModifierEntry
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.model.Theme
import viewforge.model.ThemeCategory
import viewforge.model.TypographyToken
import viewforge.model.Ulid
import viewforge.model.findById
import viewforge.model.locate
import viewforge.model.subtreeContains
import viewforge.model.withFreshIds
import java.nio.file.Path

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
     * Whether the canvas previews the project theme's **dark** values (H2). Transient view state, not
     * part of the document — the theme stores both light and dark; this only picks which half the
     * canvas shows. The editor chrome's own theme is separate (FEATURES S3).
     */
    var canvasDark: Boolean by mutableStateOf(false)
        private set

    /**
     * Whether each side panel is shown (S1, #39). Transient editor-chrome state — never part of the
     * document. The View menu toggles these; the shell hides the panel (and its divider) when false.
     * The canvas has no flag: it is always visible, so hiding every panel still leaves something to
     * edit. Cross-session persistence of the layout is a separate follow-up.
     */
    var paletteVisible: Boolean by mutableStateOf(true)
        private set
    var treeVisible: Boolean by mutableStateOf(true)
        private set
    var inspectorVisible: Boolean by mutableStateOf(true)
        private set

    /**
     * The canvas zoom & pan (C5). Transient view state — where the editor is looking, never part of
     * the document. The menu, keyboard shortcuts and canvas gestures all mutate this one value; the
     * canvas realises it as a single `graphicsLayer`, so hit-testing stays correct at every level.
     */
    var viewport: CanvasViewport by mutableStateOf(CanvasViewport())
        private set

    /**
     * Whether the space bar is held, i.e. the canvas is in pan mode (C5, space-drag to pan). The
     * shell's focus-aware key handler owns this — it's the one place that knows the space bar isn't
     * being typed into a field — and the canvas reads it to switch a drag from select to pan.
     */
    var isSpaceHeld: Boolean by mutableStateOf(false)

    /**
     * The file this document is saved to, or null when it has never been saved (a fresh [newDocument]).
     * Drives Save vs Save As and is set on open/save (D1). Transient session state, not part of the
     * document.
     */
    var currentPath: Path? by mutableStateOf(null)
        private set

    /**
     * Whether the document has unsaved edits (D1). Set by every mutation ([execute]/[undo]/[redo]);
     * cleared on save ([markSaved]) and when a document is opened or created ([replaceDocument]). The
     * File menu gates Save on this, and the toolbar shows an unsaved-marker.
     *
     * It is a plain flag: an edit-then-undo back to the last-saved state still reads dirty. That errs
     * toward *offering* a redundant save rather than ever silently dropping a real change — the safe
     * direction. (A precise saved-marker is a possible refinement, not needed here.)
     */
    var isDirty: Boolean by mutableStateOf(false)
        private set

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
        isDirty = true
        reconcileSelection(selectAfter)
    }

    fun undo() {
        if (!history.canUndo) return
        document = history.undo(document)
        isDirty = true
        reconcileSelection(selectedId)
    }

    fun redo() {
        if (!history.canRedo) return
        document = history.redo(document)
        isDirty = true
        reconcileSelection(selectedId)
    }

    private fun reconcileSelection(desired: NodeId?) {
        selectedId = desired?.takeIf { activeScreen?.root?.findById(it) != null }
    }

    // --- document session (D1) --------------------------------------------------------------------

    /**
     * Swap in a whole new [document] — from opening a file or creating one — and reset everything that
     * belonged to the old one: history (a closed document's undo stack is meaningless), selection,
     * clipboard, and the canvas view. [path] records where it came from (null for a never-saved New);
     * the document starts clean. This is the *only* place the document is replaced wholesale; every
     * other edit goes through a command.
     */
    fun replaceDocument(project: Project, path: Path?) {
        document = project
        history.clear()
        selectedId = null
        clipboard = null
        viewport = CanvasViewport()
        isSpaceHeld = false
        activeScreenId = project.screens.firstOrNull()?.id
        currentPath = path
        isDirty = false
    }

    /**
     * Start a fresh, unsaved document with a single empty screen (File → New). It keeps the current
     * document's [framework][Project.framework] — the editor stays bound to the same target — and roots
     * the screen in the catalog's first container type, so New works for any framework without naming
     * one here.
     */
    fun newDocument() {
        val rootType = catalog.palette.firstOrNull { catalog.acceptsChildren(it.type) }?.type
            ?: catalog.palette.first().type
        val screen = Screen(id = Ulid.next(), name = "Screen 1", root = catalog.newNode(rootType))
        replaceDocument(
            Project(id = Ulid.next(), name = "Untitled", framework = document.framework, screens = listOf(screen)),
            path = null,
        )
    }

    /** Record that the document was just saved to [path]: it becomes the current file and is now clean. */
    fun markSaved(path: Path) {
        currentPath = path
        isDirty = false
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

    // --- property & modifier editing (M5) ---------------------------------------------------------

    /** Set (or clear, when [value] is null) a node prop; live-updates the canvas. Coalesces per prop (D3). */
    fun setProp(nodeId: NodeId, key: String, value: PropValue?) {
        val screen = activeScreen ?: return
        execute(SetProp(screen.id, nodeId, key, value), selectAfter = selectedId)
    }

    /** Reset a prop to its schema default (I7) — removes it when the default is absent. */
    fun resetProp(nodeId: NodeId, def: PropDefinition) {
        setProp(nodeId, def.name, def.default)
    }

    /**
     * Append a modifier of [type] (with its schema defaults) to a node's chain. Ids are freshly
     * generated; order is preserved, new entry last (the user reorders via drag).
     */
    fun addModifier(nodeId: NodeId, type: String) {
        val screen = activeScreen ?: return
        val node = screen.root.findById(nodeId) ?: return
        val def = catalog.modifierDef(type) ?: return
        val args = def.args.mapNotNull { arg -> arg.default?.let { arg.name to it } }.toMap()
        val entry = ModifierEntry(id = Ulid.next(), type = type, args = args)
        execute(SetModifiers(screen.id, nodeId, node.modifiers + entry), selectAfter = selectedId)
    }

    /** Remove the modifier [modifierId] from a node's chain. */
    fun removeModifier(nodeId: NodeId, modifierId: String) {
        val screen = activeScreen ?: return
        val node = screen.root.findById(nodeId) ?: return
        execute(
            SetModifiers(
                screen.id,
                nodeId,
                node.modifiers.filterNot {
                    it.id == modifierId
                },
            ),
            selectAfter = selectedId,
        )
    }

    /** Enable/disable a modifier without deleting it (DATA_MODEL §7). */
    fun toggleModifier(nodeId: NodeId, modifierId: String) {
        val screen = activeScreen ?: return
        val node = screen.root.findById(nodeId) ?: return
        val updated = node.modifiers.map { if (it.id == modifierId) it.copy(enabled = !it.enabled) else it }
        execute(SetModifiers(screen.id, nodeId, updated), selectAfter = selectedId)
    }

    /**
     * Reorder a node's modifier chain, moving the entry at [from] to index [to]. Order is semantic
     * (ADR-005), so this is a real edit, not cosmetic. Out-of-range indices are ignored.
     */
    fun moveModifier(nodeId: NodeId, from: Int, to: Int) {
        val screen = activeScreen ?: return
        val node = screen.root.findById(nodeId) ?: return
        val list = node.modifiers
        if (from !in list.indices || to !in list.indices || from == to) return
        val reordered = list.toMutableList().apply { add(to, removeAt(from)) }
        execute(SetModifiers(screen.id, nodeId, reordered), selectAfter = selectedId)
    }

    /** Set (or clear) one arg of a modifier; live-updates and coalesces per arg (D3). */
    fun setModifierArg(nodeId: NodeId, modifierId: String, key: String, value: PropValue?) {
        val screen = activeScreen ?: return
        execute(SetModifierArg(screen.id, nodeId, modifierId, key, value), selectAfter = selectedId)
    }

    // --- theme editing (M8) -----------------------------------------------------------------------

    /** The project theme the editor edits and the canvas renders (H1). */
    val theme: Theme get() = document.theme

    /** Toggle the canvas between the theme's light and dark values (H2). View state, not an edit. */
    fun toggleCanvasDark() {
        canvasDark = !canvasDark
    }

    /** Show/hide the palette panel (S1). */
    fun togglePalette() {
        paletteVisible = !paletteVisible
    }

    /** Show/hide the tree panel (S1). */
    fun toggleTree() {
        treeVisible = !treeVisible
    }

    /** Show/hide the inspector panel (S1). */
    fun toggleInspector() {
        inspectorVisible = !inspectorVisible
    }

    // --- canvas viewport (C5) ---------------------------------------------------------------------

    /** Zoom the canvas in one step (View → Zoom In / Ctrl +). */
    fun zoomIn() {
        viewport = viewport.zoomedIn()
    }

    /** Zoom the canvas out one step (View → Zoom Out / Ctrl −). */
    fun zoomOut() {
        viewport = viewport.zoomedOut()
    }

    /** Multiply the zoom by [factor] (scroll-wheel zoom), clamped. */
    fun zoomBy(factor: Float) {
        viewport = viewport.zoomedBy(factor)
    }

    /** Reset the canvas to 100% at the origin (View → Reset Zoom / Ctrl 0). */
    fun resetZoom() {
        viewport = viewport.reset()
    }

    /** Pan the canvas by a window-space delta (space-drag). */
    fun panBy(dx: Float, dy: Float) {
        viewport = viewport.pannedBy(dx, dy)
    }

    /**
     * Apply a whole-theme edit through history (undoable, live). [coalesceKey] collapses a run of edits
     * to the *same* token field (a hex scrub, a size stepper) into one undo step (ADR-017); null — used
     * by add/remove/rename — never coalesces, so structural theme changes stay discrete.
     */
    private fun editTheme(coalesceKey: Any?, label: String, transform: (Theme) -> Theme) {
        execute(SetTheme(transform(theme), coalesceKey, label), selectAfter = selectedId)
    }

    /** A stable coalesce key for continuous edits to one token's fields (e.g. scrubbing a hex value). */
    private fun tokenKey(category: String, name: String): Any = "$category.$name"

    // colors ---------------------------------------------------------------------------------------

    /** Set a color token's light/dark pair (H1). Coalesces per token so scrubbing is one undo step. */
    fun setColor(name: String, pair: ColorPair) = editTheme(tokenKey(ThemeCategory.COLORS, name), "Edit color $name") {
        it.copy(colors = it.colors + (name to pair))
    }

    /** Add a new color token with a neutral default pair; a no-op if [name] is blank or already exists. */
    fun addColor(name: String, pair: ColorPair = ColorPair("#000000", "#FFFFFF")) {
        if (name.isBlank() || name in theme.colors) return
        editTheme(null, "Add color $name") { it.copy(colors = it.colors + (name to pair)) }
    }

    /** Remove a color token. Existing references fall back gracefully at render/codegen (never crash). */
    fun removeColor(name: String) = editTheme(null, "Remove color $name") { it.copy(colors = it.colors - name) }

    /** Rename a color token, propagating to every reference across all screens (H5). */
    fun renameColor(from: String, to: String) = renameToken(ThemeCategory.COLORS, from, to)

    // typography -----------------------------------------------------------------------------------

    fun setTypography(name: String, token: TypographyToken) =
        editTheme(tokenKey(ThemeCategory.TYPOGRAPHY, name), "Edit type $name") {
            it.copy(typography = it.typography + (name to token))
        }

    fun addTypography(name: String, token: TypographyToken = TypographyToken(fontSize = 16, lineHeight = 24)) {
        if (name.isBlank() || name in theme.typography) return
        editTheme(null, "Add type $name") { it.copy(typography = it.typography + (name to token)) }
    }

    fun removeTypography(name: String) =
        editTheme(null, "Remove type $name") { it.copy(typography = it.typography - name) }

    fun renameTypography(from: String, to: String) = renameToken(ThemeCategory.TYPOGRAPHY, from, to)

    // shapes ---------------------------------------------------------------------------------------

    fun setShape(name: String, corner: Int) = editTheme(tokenKey(ThemeCategory.SHAPES, name), "Edit shape $name") {
        it.copy(shapes = it.shapes + (name to corner))
    }

    fun addShape(name: String, corner: Int = 8) {
        if (name.isBlank() || name in theme.shapes) return
        editTheme(null, "Add shape $name") { it.copy(shapes = it.shapes + (name to corner)) }
    }

    fun removeShape(name: String) = editTheme(null, "Remove shape $name") { it.copy(shapes = it.shapes - name) }

    fun renameShape(from: String, to: String) = renameToken(ThemeCategory.SHAPES, from, to)

    // spacing --------------------------------------------------------------------------------------

    fun setSpacing(name: String, dp: Int) = editTheme(tokenKey(ThemeCategory.SPACING, name), "Edit spacing $name") {
        it.copy(spacing = it.spacing + (name to dp))
    }

    fun addSpacing(name: String, dp: Int = 8) {
        if (name.isBlank() || name in theme.spacing) return
        editTheme(null, "Add spacing $name") { it.copy(spacing = it.spacing + (name to dp)) }
    }

    fun removeSpacing(name: String) = editTheme(null, "Remove spacing $name") { it.copy(spacing = it.spacing - name) }

    fun renameSpacing(from: String, to: String) = renameToken(ThemeCategory.SPACING, from, to)

    /** Rename a token in [category]; a no-op if the rename is invalid (source absent / target taken). */
    private fun renameToken(category: String, from: String, to: String) {
        if (from == to || to.isBlank()) return
        execute(RenameThemeToken(category, from, to), selectAfter = selectedId)
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

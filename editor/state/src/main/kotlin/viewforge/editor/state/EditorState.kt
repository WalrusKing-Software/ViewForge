package viewforge.editor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import viewforge.command.AddNode
import viewforge.command.AddScreen
import viewforge.command.Command
import viewforge.command.CompositeCommand
import viewforge.command.History
import viewforge.command.MoveNode
import viewforge.command.RemoveNode
import viewforge.command.RemoveScreen
import viewforge.command.RenameNode
import viewforge.command.RenameScreen
import viewforge.command.RenameThemeToken
import viewforge.command.SetModifierArg
import viewforge.command.SetModifiers
import viewforge.command.SetNodeFlags
import viewforge.command.SetPreviewProfile
import viewforge.command.SetProp
import viewforge.command.SetTheme
import viewforge.command.extractComponent
import viewforge.command.promoteToParameter
import viewforge.model.ChildAddress
import viewforge.model.ColorPair
import viewforge.model.ComponentDef
import viewforge.model.ModifierEntry
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Parameter
import viewforge.model.ParameterType
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.model.Theme
import viewforge.model.ThemeCategory
import viewforge.model.TypographyToken
import viewforge.model.Ulid
import viewforge.model.UserComponent
import viewforge.model.findById
import viewforge.model.insertionWouldCycle
import viewforge.model.locate
import viewforge.model.subtreeContains
import viewforge.model.withFreshIds
import viewforge.prefs.EditorPreferences
import viewforge.prefs.FavoriteComponents
import viewforge.prefs.PanelLayout
import viewforge.prefs.RecentProjects
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
     * The clipboard for copy/cut/paste (D5, C10). Transient view state, never part of the document; holds
     * immutable node snapshots (one per copied top-level selection, in document order) that paste clones
     * with fresh ids. Empty when nothing has been copied.
     */
    var clipboard: List<Node> by mutableStateOf(emptyList())
        private set

    /** Which screen the canvas shows. */
    var activeScreenId: String? by mutableStateOf(initial.screens.firstOrNull()?.id)

    val activeScreen: Screen?
        get() = document.screens.firstOrNull { it.id == activeScreenId } ?: document.screens.firstOrNull()

    /**
     * The reusable component currently opened for in-place editing (D7 follow-up, #61), or null when the
     * active **screen** is the edit surface. Transient view state, never part of the document.
     */
    var editingComponentId: String? by mutableStateOf(null)
        private set

    /** The opened component, or null when editing the screen or the id no longer resolves (e.g. after an undo). */
    private val editingComponent: ComponentDef?
        get() = editingComponentId?.let { id -> document.components.firstOrNull { it.id == id } }

    /**
     * The root node the editor is currently editing: the opened component's root, or the active screen's
     * (ADR-027). The canvas draws it, the tree lists it, and selection plus every node command operate
     * within it — so opening a component makes "instances update on edit" a live gesture. Falls back to
     * the screen when no component is open or the open id has vanished.
     */
    val activeEditRoot: Node?
        get() = editingComponent?.root ?: activeScreen?.root

    /** The id of the current edit surface — a component id when one is open, else the active screen's. */
    val activeEditRootId: String?
        get() = editingComponent?.id ?: activeScreen?.id

    /** The name of the component open for in-place editing (for the breadcrumb), or null when editing a screen. */
    val editingComponentName: String?
        get() = editingComponent?.name

    /**
     * What the code preview should show (G3, #69): the open component when one is being edited in place,
     * else the active screen. Null only for an empty project with no screen. Follows [activeEditRoot].
     */
    val previewTarget: PreviewTarget?
        get() = editingComponent?.let { PreviewTarget.OfComponent(it) }
            ?: activeScreen?.let { PreviewTarget.OfScreen(it) }

    /** Open reusable component [id] for in-place editing; selection resets into the component's own tree. */
    fun openComponent(id: String) {
        if (document.components.none { it.id == id }) return
        editingComponentId = id
        selectedIds = emptyList()
        selectionAnchor = null
    }

    /** Return to editing the active screen, closing any open component (selection clears). */
    fun closeComponent() {
        editingComponentId = null
        selectedIds = emptyList()
        selectionAnchor = null
    }

    /**
     * If [node] is a `vforge.userComponent` instance whose definition resolves, open that component for
     * in-place editing and return true; otherwise a no-op returning false. The double-click-to-enter
     * gesture on the canvas and tree calls this, so a non-instance falls back to its usual double-click
     * action (#68).
     */
    fun openInstanceComponent(node: Node): Boolean {
        val id = componentOfInstance(node)?.id ?: return false
        openComponent(id)
        return true
    }

    /**
     * Whether the canvas previews the project theme's **dark** values (H2). Transient view state, not
     * part of the document — the theme stores both light and dark; this only picks which half the
     * canvas shows. The editor chrome's own theme is separate (FEATURES S3).
     */
    var canvasDark: Boolean by mutableStateOf(false)
        private set

    /**
     * Whether the canvas overlays a debug outline around every layout container (#117). Editor-only view
     * state, never serialized and never touching the document or codegen — like [canvasDark], it only
     * changes what the editor draws on top of the rendered UI. Reuses the per-node content-space bounds the
     * overlay already keeps for hit-testing (ADR-009, #116), so it costs one extra draw pass and no model.
     */
    var showBorders: Boolean by mutableStateOf(false)
        private set

    /**
     * Whether the canvas draws static alignment guides for the primary selection (C11, #118). Editor-only
     * view state, never serialized — like [showBorders], it only changes what the editor overlays. When on,
     * the overlay draws a guide line wherever the selected node's edges/center line up with a sibling's or
     * the parent's, reusing the per-node content-space bounds (#116). (Drag-time snapping is #129, blocked.)
     */
    var showGuides: Boolean by mutableStateOf(false)
        private set

    /**
     * Whether the canvas is in **interactive preview** mode (C13, #120): a run-mode toggle that lets the
     * user click buttons, type in fields, and flip toggles on the live rendered UI instead of editing it.
     * Editor-only view state, never serialized — like [showBorders], it only changes how the canvas behaves.
     * The canvas removes its selection overlay (so pointer events reach the real components) and renders with
     * interactivity on; selection is untouched, so leaving preview restores the outlines. Reset on a document
     * swap so a fresh document always opens in edit mode.
     */
    var interactivePreview: Boolean by mutableStateOf(false)
        private set

    /**
     * Whether the editor *chrome* (panels, menus, toolbar) uses the dark color scheme (S3, #104). This is
     * the editor's own theme, wholly independent of [canvasDark] (the project preview, H2): changing one
     * never changes the other. Seeded from persisted prefs at startup and toggled from the View menu, which
     * persists it back (mirrors the panel-visibility flags). Defaults to dark, matching the previously
     * hardcoded chrome so an upgrading user sees no change.
     */
    var chromeDark: Boolean by mutableStateOf(true)

    /**
     * How often crash-recovery autosave writes while there are unsaved edits, in seconds (S5, #55/#105).
     * Seeded from prefs at startup and editable live in the Preferences dialog; the shell's autosave timer
     * observes it, so a change re-drives the cadence without a restart. Clamped through the single
     * [EditorPreferences.clampAutosaveInterval] bound so a bad value can't hammer the disk or disable recovery.
     */
    var autosaveIntervalSeconds: Int by mutableStateOf(EditorPreferences.DEFAULT_AUTOSAVE_INTERVAL_SECONDS)
        private set

    /**
     * The directory an Export dialog opens in by default (S5, #105); blank means "let the OS picker choose".
     * Seeded from prefs and editable in the Preferences dialog. Transient editor chrome — never document data.
     */
    var defaultExportPath: String by mutableStateOf("")
        private set

    /**
     * How many undo entries the editor keeps (S5, #105). Setting it also re-caps the live [history] so a
     * lowered depth trims immediately. Seeded from prefs and editable in the Preferences dialog. Clamped
     * through the single [EditorPreferences.clampHistoryDepth] bound.
     */
    var historyDepth: Int by mutableStateOf(EditorPreferences.DEFAULT_HISTORY_DEPTH)
        private set

    /** Set the autosave cadence (S5), clamped to the sane range. The shell's timer observes this value. */
    fun updateAutosaveInterval(seconds: Int) {
        autosaveIntervalSeconds = EditorPreferences.clampAutosaveInterval(seconds)
    }

    /** Set the default export directory (S5); blank clears it. */
    fun updateDefaultExportPath(path: String) {
        defaultExportPath = path.trim()
    }

    /** Set the undo depth (S5), clamped, and re-cap the live history so a lower value takes effect at once. */
    fun updateHistoryDepth(entries: Int) {
        historyDepth = EditorPreferences.clampHistoryDepth(entries)
        history.limit = historyDepth
    }

    /**
     * Seed the live editor-setting scalars from persisted prefs at startup (S3/S5): chrome theme, autosave
     * cadence, undo depth, and default export path. Called once by `:app` before the first frame, alongside
     * [applyLayout] and [applyRecentProjects]. View state only — it never touches the document or history
     * contents (only the history *cap*).
     */
    fun applyPreferences(prefs: EditorPreferences) {
        chromeDark = prefs.chromeDark
        updateAutosaveInterval(prefs.autosaveIntervalSeconds)
        updateHistoryDepth(prefs.historyDepth)
        updateDefaultExportPath(prefs.defaultExportPath)
    }

    /**
     * Whether each side panel is shown (S1, #39). Transient editor-chrome state — never part of the
     * document. The View menu toggles these; the shell hides the panel (and its divider) when false.
     * The canvas has no flag: it is always visible, so hiding every panel still leaves something to
     * edit. Persisted across sessions with the panel widths via [PanelLayout] (#43).
     */
    var paletteVisible: Boolean by mutableStateOf(true)
        private set
    var treeVisible: Boolean by mutableStateOf(true)
        private set
    var inspectorVisible: Boolean by mutableStateOf(true)
        private set

    /**
     * Each side panel's width in dp (S1, #43). Transient chrome, like the visibility flags — the shell
     * realises them as `.width(...)` and drags mutate them live. Stored as plain [Float] dp magnitudes
     * (this module has only the Compose *runtime*, not the UI unit types); the shell attaches `.dp`.
     * Persisted across sessions via [PanelLayout] — [applyLayout] restores them, [panelLayout] snapshots
     * them for saving. Both restore and drag clamp through [PanelLayout.clampWidth], the one bound.
     */
    var paletteWidth: Float by mutableStateOf(PanelLayout.DEFAULT_PALETTE_WIDTH)
        private set
    var treeWidth: Float by mutableStateOf(PanelLayout.DEFAULT_TREE_WIDTH)
        private set
    var inspectorWidth: Float by mutableStateOf(PanelLayout.DEFAULT_INSPECTOR_WIDTH)
        private set

    /**
     * The live code-preview panel (G3, #50). Editor chrome like the side panels: hidden by default,
     * wider since it shows source, and — as of #52 — **persisted** across sessions via [PanelLayout]
     * ([applyLayout] restores it, [panelLayout] snapshots it). The width clamps through the same
     * [PanelLayout] bound. The panel itself is read-only.
     */
    var codePreviewVisible: Boolean by mutableStateOf(false)
        private set
    var codePreviewWidth: Float by mutableStateOf(PanelLayout.DEFAULT_CODE_PREVIEW_WIDTH)
        private set

    /**
     * Whether the code-preview panel soft-wraps long lines instead of scrolling horizontally (#115).
     * Persisted alongside the panel layout ([applyLayout]/[panelLayout]); the panel reads it to pick
     * `softWrap` and whether to attach a horizontal scroll. Defaults off, so upgrading users keep the
     * previous scroll behaviour.
     */
    var codePreviewWrap: Boolean by mutableStateOf(false)
        private set

    /**
     * The canvas zoom & pan (C5). Transient view state — where the editor is looking, never part of
     * the document. The menu, keyboard shortcuts and canvas gestures all mutate this one value; the
     * canvas realises it as a single `graphicsLayer`, so hit-testing stays correct at every level.
     */
    var viewport: CanvasViewport by mutableStateOf(CanvasViewport())
        private set

    /**
     * The canvas's last-measured available area and density (C6 auto-fit, #59), recorded by the canvas
     * each layout so the on-demand [fitToFrame] and the View → Fit menu item have a size to fit to.
     * Transient view state like [viewport]; null until the canvas has been measured once.
     */
    var canvasFitBounds: CanvasFitBounds? by mutableStateOf(null)

    /**
     * Whether the space bar is held, i.e. the canvas is in pan mode (C5, space-drag to pan). The
     * shell's focus-aware key handler owns this — it's the one place that knows the space bar isn't
     * being typed into a field — and the canvas reads it to switch a drag from select to pan.
     */
    var isSpaceHeld: Boolean by mutableStateOf(false)

    /**
     * Whether the measure/spacing overlay is active (C12, #119) — i.e. the measure key is held. Like
     * [isSpaceHeld], the shell's focus-aware key handler owns it (it's the one place that knows the key
     * isn't being typed into a field), and the canvas reads it to draw the gaps from the primary selection
     * to its parent container's edges. Transient view state, never serialized.
     */
    var isMeasuring: Boolean by mutableStateOf(false)

    /**
     * A pending request to rename a node inline (T3, F2). Transient view state: the shell's focus-aware
     * key handler sets it (F2 acts on the selection regardless of which surface has focus), and the tree
     * panel — which owns the inline edit field — observes it, enters rename mode for that node, and
     * clears it. Null when no rename is pending.
     */
    var renameRequest: NodeId? by mutableStateOf(null)
        private set

    /**
     * An in-flight palette→canvas drag (P2a). Transient view state spanning two panels: the palette is
     * the drag *source* (it sets [paletteDragType] and streams the pointer in window space via
     * [updatePaletteDrag]); the canvas overlay is the drop *surface* (it resolves the geometry against
     * its node bounds and publishes the result via [resolvePaletteDrop]). Kept here, on the object both
     * panels already share, so neither module has to name the other. Pointer coordinates are plain
     * [Float]s (this module has no Compose-ui geometry types), mirroring the panel widths.
     */
    var paletteDragType: String? by mutableStateOf(null)
        private set
    var paletteDragX: Float? by mutableStateOf(null)
        private set
    var paletteDragY: Float? by mutableStateOf(null)
        private set

    /**
     * The user-component id being dragged, when the drag source is a user component (P6a); null for a
     * framework built-in. Kept beside [paletteDragType] so the release knows whether to insert an
     * instance node or a fresh built-in — the canvas overlay treats the drag opaquely (it only needs
     * [paletteDragType]'s presence), so this rides along without touching the geometry resolution.
     */
    private var paletteDragComponentId: String? = null

    /** The canvas-resolved drop for the live palette drag; a plain field — only [dropPaletteDrag] reads it. */
    private var paletteDropAddress: ChildAddress? = null

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
     * Recently opened/saved project paths, most-recent first (D8, #88). Transient view state: applied from
     * prefs at launch ([applyRecentProjects]) and updated on a successful open/save ([noteRecentProject]).
     * The File menu's Open Recent reads it; the shell persists it. Not document data — it is per-user
     * history, so it lives here beside the other session state, never in the `.vforge` file.
     */
    var recentProjects: List<String> by mutableStateOf(emptyList())
        private set

    /**
     * Palette entries the user has pinned (P5a, #121), by stable [PaletteEntry.key] in starring order.
     * Applied from prefs at launch ([applyFavoriteComponents]) and toggled from the palette; the shell
     * persists it. Per-user chrome, never document data — like [recentProjects].
     */
    var favoriteComponents: List<String> by mutableStateOf(emptyList())
        private set

    /**
     * Palette entries used most recently, most-recent first (P5a, #121). **Session-only** transient state:
     * recorded on every insert ([noteRecentComponent]) and reset when the app restarts, deliberately *not*
     * persisted — recording on each insert would write the prefs file constantly. Capped at
     * [MAX_RECENT_COMPONENTS].
     */
    var recentComponents: List<String> by mutableStateOf(emptyList())
        private set

    /**
     * The selection, as an **ordered** list of node ids (C10). The last entry is the *primary*: the node
     * the inspector focuses. Empty means nothing is selected. Shared, observable state so canvas and tree
     * stay in sync (T1). Selection is transient view state — it lives here, never in the IR. Always a
     * subset of the active edit surface; every entry resolves against [activeEditRoot] (see
     * [reconcileSelection]).
     */
    var selectedIds: List<NodeId> by mutableStateOf(emptyList())
        private set

    /**
     * The pivot a shift-click range extends from (C10). A plain click or a ctrl-click sets it; a
     * shift-click extends the range anchor..target without moving it, so successive shift-clicks all
     * measure from the same fixed pivot (the file-explorer model), rather than from wherever the last
     * click landed. Null when nothing is selected.
     */
    var selectionAnchor: NodeId? by mutableStateOf(null)
        private set

    /**
     * The primary (anchor) selected id, or null. Derived from [selectedIds] — the last, most recently
     * added entry. This is the single-selection view that most of the editor reads; multi-select is
     * additive on top of it (C10).
     */
    val selectedId: NodeId?
        get() = selectedIds.lastOrNull()

    /** The primary selected [Node] resolved against the active edit surface (screen or open component), or null. */
    val selectedNode: Node?
        get() = selectedId?.let { activeEditRoot?.findById(it) }

    /** Every selected [Node], in selection order, resolved against the active edit surface. */
    val selectedNodes: List<Node>
        get() = activeEditRoot?.let { root -> selectedIds.mapNotNull { root.findById(it) } } ?: emptyList()

    /** Whether [id] is part of the current selection (C10). */
    fun isSelected(id: NodeId): Boolean = id in selectedIds

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
    val undoLabel: String? get() = history.undoLabel
    val redoLabel: String? get() = history.redoLabel
    val canPaste: Boolean get() = clipboard.any { !wouldInsertingCycle(it) }

    /**
     * Select a single node by id, replacing any existing selection, or null to clear (C10: plain click).
     * A **locked** node cannot be selected (T4); the request is simply ignored so the current selection
     * stands.
     */
    fun select(id: NodeId?) {
        if (id == null) {
            selectedIds = emptyList()
            selectionAnchor = null
            return
        }
        if (activeEditRoot?.findById(id)?.locked == true) return
        selectedIds = listOf(id)
        selectionAnchor = id
    }

    /**
     * Add [id] to the selection if absent, or remove it if already selected (C10: ctrl/cmd-click). Adding
     * makes [id] the new primary and the new range pivot. A **locked** node cannot be added (T4); an
     * already-selected locked node can still be toggled off. Toggling off the only selected node clears it.
     */
    fun toggleSelection(id: NodeId) {
        if (id in selectedIds) {
            selectedIds = selectedIds - id
            selectionAnchor = selectedIds.lastOrNull()
            return
        }
        if (activeEditRoot?.findById(id)?.locked == true) return
        selectedIds = selectedIds + id
        selectionAnchor = id
    }

    /**
     * Shift-click range select (C10): select every node from the [selectionAnchor] pivot to [target]
     * inclusive, along the panel's visible [order] (the tree's flattened rows — the canvas has no natural
     * order, so it treats shift like a plain toggle instead). [target] becomes the primary so the inspector
     * focuses the clicked end, while the pivot stays put for the next shift-click. Locked nodes in the span
     * are skipped (T4). Falls back to a plain [select] when there is no pivot, or either end is off-list.
     */
    fun extendSelectionTo(target: NodeId, order: List<NodeId>) {
        val pivot = selectionAnchor ?: return select(target)
        val from = order.indexOf(pivot)
        val to = order.indexOf(target)
        if (from < 0 || to < 0) return select(target)
        val span = if (from <= to) order.subList(from, to + 1) else order.subList(to, from + 1).asReversed()
        val root = activeEditRoot
        val selectable = span.filter { root?.findById(it)?.locked != true }
        // Keep [target] last (primary); the pivot is unchanged so successive shift-clicks measure from it.
        selectedIds = selectable
    }

    /**
     * Replace the entire selection with [ids] at once (C10 marquee, #93): a rubber-band drag resolves to
     * a set of nodes rather than one. Ids absent from the active edit surface or naming a **locked** node
     * (T4) are dropped; the last surviving id becomes the primary and the range pivot. An empty result
     * clears the selection, like a click on empty canvas.
     */
    fun setSelection(ids: List<NodeId>) {
        val root = activeEditRoot
        val kept = ids.filter { id -> root?.findById(id)?.locked == false }
        selectedIds = kept
        selectionAnchor = kept.lastOrNull()
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

    /**
     * Like [execute] but leaves a whole set of nodes selected afterward (C10 batch ops) — each id is
     * honored only if it still exists. Used by batch delete/duplicate/paste so a multi-selection survives
     * the command instead of collapsing to a single node.
     */
    private fun executeSelectingAll(command: Command, selectAfter: List<NodeId>) {
        document = history.execute(command, document)
        isDirty = true
        val root = activeEditRoot
        selectedIds = selectAfter.filter { root?.findById(it) != null }
        selectionAnchor = selectedIds.lastOrNull()
    }

    /**
     * The selected nodes with no selected ancestor, in selection order — the set batch delete/duplicate/
     * copy act on, so a node is never handled twice (once on its own and once inside a selected ancestor).
     */
    private fun selectionTopLevel(): List<Node> {
        val nodes = selectedNodes
        return nodes.filter { node -> nodes.none { it.id != node.id && it.findById(node.id) != null } }
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
        selectedIds = listOfNotNull(desired?.takeIf { activeEditRoot?.findById(it) != null })
        selectionAnchor = selectedIds.lastOrNull()
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
        selectedIds = emptyList()
        selectionAnchor = null
        clipboard = emptyList()
        viewport = CanvasViewport()
        isSpaceHeld = false
        isMeasuring = false
        interactivePreview = false // a fresh document opens in edit mode, not run mode (C13, #120)
        activeScreenId = project.screens.firstOrNull()?.id
        editingComponentId = null
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
        // "Screen1" (no space) so it is a legal identifier and a fresh document exports without a rename (GC-3).
        val screen = Screen(id = Ulid.next(), name = "Screen1", root = catalog.newNode(rootType))
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

    /**
     * Restore autosaved work recovered at launch (D4): swap in [project] under its original [path] (null
     * if it was never saved), then mark it **dirty** — recovered work is by definition ahead of what is on
     * disk, so it must offer to be saved and must not be silently dropped. Otherwise a whole-document swap
     * like [replaceDocument].
     */
    fun restoreRecovered(project: Project, path: Path?) {
        replaceDocument(project, path)
        isDirty = true
    }

    // --- screen session (D6) ----------------------------------------------------------------------
    // Screens are document data, so add/remove/rename all go through commands (rule 3) and thus
    // undo/redo. The active screen is transient view state, managed here alongside the edit.

    /**
     * Why [name] cannot be used for the screen identified by [excludingId] (null when naming a new
     * screen), or null if it is acceptable. A name must be non-blank, a legal identifier for the
     * framework (GC-3, via the catalog), and unique among the other screens — two screens sharing a
     * name would generate colliding composables/files (D6). This is the edit-time check the switcher
     * shows so a bad name fails loudly here, not at export.
     */
    fun screenNameError(name: String, excludingId: String?): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isBlank() -> "Name cannot be empty"
            !catalog.isValidScreenName(trimmed) -> "Not a valid name (must be a legal Kotlin identifier)"
            document.screens.any { it.id != excludingId && it.name == trimmed } ->
                "A screen named “$trimmed” already exists"
            else -> null
        }
    }

    /** Rename screen [id]; a no-op (with no history entry) if the name is invalid or a duplicate. */
    fun renameScreen(id: String, name: String) {
        val trimmed = name.trim()
        if (screenNameError(trimmed, excludingId = id) != null) return
        execute(RenameScreen(id, trimmed), selectAfter = selectedId)
    }

    /**
     * The device profile the active screen is framed to on the canvas (C6), resolving the screen's stored
     * [previewProfile][Screen.previewProfile] against the [DeviceProfiles] registry — falling back to the
     * default for a screen with no or an unrecognized profile, so the canvas always has a frame size.
     */
    val activeDeviceProfile: DeviceProfile
        get() = DeviceProfiles.forId(activeScreen?.previewProfile)

    /** Set the active screen's device preview profile (C6); undoable, preview-only (no codegen effect). */
    fun setPreviewProfile(profileId: String) {
        val screen = activeScreen ?: return
        if (screen.previewProfile == profileId) return
        execute(SetPreviewProfile(screen.id, profileId), selectAfter = selectedId)
    }

    /**
     * Add a fresh, empty screen after the last one and make it active. It is auto-named to the first
     * unused `Screen<n>` — a valid identifier, so it exports without a rename — and rooted in the
     * catalog's first container type, mirroring [newDocument].
     */
    fun addScreen() {
        val rootType = catalog.palette.firstOrNull { catalog.acceptsChildren(it.type) }?.type
            ?: catalog.palette.first().type
        val screen = Screen(id = Ulid.next(), name = uniqueScreenName(), root = catalog.newNode(rootType))
        execute(AddScreen(screen, document.screens.size), selectAfter = null)
        activeScreenId = screen.id
    }

    /**
     * Remove screen [id]; a no-op if it is the only screen (a project always keeps one). If the removed
     * screen was active, the previous screen (or the first) becomes active. Selection is left to
     * reconcile against the new active screen.
     */
    fun removeScreen(id: String) {
        if (document.screens.size <= 1) return
        val removingActive = id == activeScreenId
        val index = document.screens.indexOfFirst { it.id == id }
        execute(RemoveScreen(id), selectAfter = null)
        if (removingActive) {
            activeScreenId = document.screens.getOrNull((index - 1).coerceAtLeast(0))?.id
        }
    }

    /** The first `Screen<n>` name (n ≥ 1) not already taken — always a legal identifier. */
    private fun uniqueScreenName(): String {
        val taken = document.screens.mapTo(HashSet()) { it.name }
        var n = 1
        while ("Screen$n" in taken) n++
        return "Screen$n"
    }

    // --- high-level operations --------------------------------------------------------------------

    /**
     * The palette shown in the UI (P1a/P6a): the framework built-ins, then this document's user
     * components as instance entries. Recomputed from the live document, so extracting or removing a
     * component updates the palette immediately. User components share one category so they cluster
     * together beneath the built-ins.
     */
    val palette: List<PaletteEntry>
        get() = catalog.palette + document.components.map {
            PaletteEntry(UserComponent.TYPE, it.name, USER_COMPONENTS_CATEGORY, componentId = it.id)
        }

    /** A fresh node for a palette entry: an instance node for a user component, else a built-in. */
    private fun paletteNode(type: String, componentId: String?): Node =
        if (componentId != null) UserComponent.instance(componentId) else catalog.newNode(type)

    /**
     * Whether inserting [node] into the current edit surface would close a reference cycle (PF-3, #70):
     * true only while a component is open for in-place editing and [node] references — directly or
     * transitively — that component (including inserting an instance of it into itself). A cycle is
     * caught at render (loud placeholder) and load (validation), but the editor refuses to *create* one:
     * the palette greys such entries out and paste is disabled. Screen editing never cycles.
     */
    fun wouldInsertingCycle(node: Node): Boolean = document.insertionWouldCycle(editingComponentId, node)

    /**
     * Whether adding palette [entry] here would form a cycle (#70) — always false for a framework
     * built-in and while editing a screen; true only for a user-component entry that would reference the
     * open component. The palette disables and explains such entries so the insert is refused up front.
     */
    fun paletteEntryWouldCycle(entry: PaletteEntry): Boolean =
        entry.componentId != null && wouldInsertingCycle(paletteNode(entry.type, entry.componentId))

    /** Add a fresh node for [entry] at the current insertion point on the active edit surface, and select it (P1a/P6a). */
    fun addFromPalette(entry: PaletteEntry) {
        val rootId = activeEditRootId ?: return
        val address = insertionAddress() ?: return
        val node = paletteNode(entry.type, entry.componentId)
        if (wouldInsertingCycle(node)) return // refuse a cycle-forming insert up front (#70)
        execute(AddNode(rootId, address, node), selectAfter = node.id)
        noteRecentComponent(entry.key) // surface it under "Recent" in the palette (P5a, #121)
    }

    /** Convenience for a framework built-in identified by [type] alone (no user-component id). */
    fun addFromPalette(type: String) = addFromPalette(PaletteEntry(type, type, ""))

    // --- palette drag-to-canvas (P2a) -------------------------------------------------------------
    // The palette drives the source half; the canvas overlay resolves the geometry and publishes the
    // target back here, so a drop is an AddNode at a *position* rather than at the selection.

    /** Begin a palette drag of [entry] (built-in or user component); pointer unknown until [updatePaletteDrag]. */
    fun beginPaletteDrag(entry: PaletteEntry) {
        paletteDragType = entry.type
        paletteDragComponentId = entry.componentId
        paletteDragX = null
        paletteDragY = null
        paletteDropAddress = null
    }

    /** Convenience for dragging a framework built-in identified by [type] alone. */
    fun beginPaletteDrag(type: String) = beginPaletteDrag(PaletteEntry(type, type, ""))

    /** Stream the drag pointer, in window space, from the palette so the canvas can resolve a drop. */
    fun updatePaletteDrag(x: Float, y: Float) {
        paletteDragX = x
        paletteDragY = y
    }

    /** The canvas publishes the drop it resolved for the live pointer ([address] null = no legal target). */
    fun resolvePaletteDrop(address: ChildAddress?) {
        paletteDropAddress = address
    }

    /**
     * Commit the in-flight palette drag: insert a fresh node of the dragged type at the canvas-resolved
     * address and select it. A no-op when the pointer isn't over a legal target. Always clears the drag.
     */
    fun dropPaletteDrag() {
        val type = paletteDragType
        val address = paletteDropAddress
        val rootId = activeEditRootId
        if (type != null && address != null && rootId != null) {
            val node = paletteNode(type, paletteDragComponentId)
            // A cycle-forming drop is refused (#70); the palette disables the drag source, so this is defence.
            if (!wouldInsertingCycle(node)) {
                execute(AddNode(rootId, address, node), selectAfter = node.id)
                noteRecentComponent(paletteDragComponentId ?: type) // record for the palette's "Recent" (P5a)
            }
        }
        cancelPaletteDrag()
    }

    /** Abandon the in-flight palette drag with no change (drag cancelled or released off-target). */
    fun cancelPaletteDrag() {
        paletteDragType = null
        paletteDragComponentId = null
        paletteDragX = null
        paletteDragY = null
        paletteDropAddress = null
    }

    /** Delete every selected node (never the root) as one undoable step, leaving the primary's parent selected. */
    fun deleteSelected() {
        val root = activeEditRoot ?: return
        val rootId = activeEditRootId ?: return
        val targets = selectionTopLevel().filter { it.id != root.id }
        if (targets.isEmpty()) return
        val parentId = selectedNode?.let { root.locate(it.id)?.parentId }
        val command = if (targets.size == 1) {
            RemoveNode(rootId, targets[0].id)
        } else {
            CompositeCommand(targets.map { RemoveNode(rootId, it.id) }, label = "Delete ${targets.size} nodes")
        }
        executeSelectingAll(command, listOfNotNull(parentId))
    }

    /** Duplicate every selected subtree (fresh ids) as its next sibling in one step, and select the clones. */
    fun duplicateSelected() {
        val root = activeEditRoot ?: return
        val rootId = activeEditRootId ?: return
        val targets = selectionTopLevel().filter { it.id != root.id } // the root has no sibling slot
        // Insert higher-index siblings first so each precomputed address stays valid as siblings shift.
        val inserts = targets.mapNotNull { node ->
            val addr = root.locate(node.id) ?: return@mapNotNull null
            addr.copy(index = addr.index + 1) to node.withFreshIds()
        }.sortedByDescending { it.first.index }
        if (inserts.isEmpty()) return
        val command = if (inserts.size == 1) {
            AddNode(rootId, inserts[0].first, inserts[0].second)
        } else {
            CompositeCommand(
                inserts.map {
                    AddNode(rootId, it.first, it.second)
                },
                label = "Duplicate ${inserts.size} nodes",
            )
        }
        executeSelectingAll(command, inserts.map { it.second.id })
    }

    // --- reusable components (D7) ------------------------------------------------------------------

    /**
     * Whether the current selection can be extracted into a reusable component: a non-root node on a
     * screen. The root is excluded — extracting it would leave the screen with nothing but an instance.
     */
    val canExtractSelection: Boolean
        get() {
            val root = activeEditRoot ?: return false
            val id = selectedId ?: return false
            return id != root.id
        }

    /**
     * Why [name] cannot name a new user component, or null if it is acceptable. Like a screen name it
     * must be a legal identifier (it becomes a composable function name, GC-3) and unique among the
     * document's components — two components sharing a name would generate colliding composables/files.
     */
    fun componentNameError(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isBlank() -> "Name cannot be empty"
            !catalog.isValidScreenName(trimmed) -> "Not a valid name (must be a legal Kotlin identifier)"
            document.components.any { it.name == trimmed } -> "A component named “$trimmed” already exists"
            else -> null
        }
    }

    /**
     * Extract the selected subtree into a new reusable component named [name], leaving an instance in its
     * place (D7). A no-op if there is no eligible selection or the name is invalid/duplicate. The subtree
     * moves into the definition (ids preserved so undo restores it intact); the instance — a thin
     * reference resolved at render/codegen time (ADR-024) — is selected.
     */
    fun extractSelectionToComponent(name: String) {
        val root = activeEditRoot ?: return
        val rootId = activeEditRootId ?: return
        val node = selectedNode ?: return
        if (node.id == root.id) return
        if (componentNameError(name) != null) return
        val id = "cmp_${Ulid.next()}"
        val component = ComponentDef(id = id, name = name.trim(), root = node)
        val instance = UserComponent.instance(id)
        execute(extractComponent(rootId, node.id, component, instance), selectAfter = instance.id)
    }

    /** The first `Component<n>` name not already taken — a legal identifier default for a fresh extraction. */
    fun uniqueComponentName(): String {
        val taken = document.components.mapTo(HashSet()) { it.name }
        var n = 1
        while ("Component$n" in taken) n++
        return "Component$n"
    }

    /**
     * Whether the prop [def] of [node] can be promoted to a component parameter (ADR-028): only while a
     * component is open for in-place editing (its definition is the edit surface), only for a value-like
     * [PropType] (`ParameterType.isPromotable`), and only when the prop is not already bound to a parameter.
     */
    fun canPromoteToParameter(node: Node, def: PropDefinition): Boolean = editingComponentId != null &&
        ParameterType.isPromotable(def.type) &&
        node.props[def.name] !is PropValue.ParamRef

    /**
     * Promote node [nodeId]'s prop [propName] to a parameter of the open component (ADR-028): derive a
     * parameter named after the prop (disambiguated if taken), typed from the prop's [PropType], defaulting
     * to the prop's current value (or its schema default), then add it and rebind the prop to a `ParamRef`
     * in one undoable step. A no-op unless a component is open and the prop is promotable.
     */
    fun promotePropToParameter(nodeId: NodeId, propName: String) {
        val componentId = editingComponentId ?: return
        val node = activeEditRoot?.findById(nodeId) ?: return
        val def = catalog.propsFor(node.type).firstOrNull { it.name == propName } ?: return
        if (!canPromoteToParameter(node, def)) return
        val typeName = ParameterType.nameFor(def.type) ?: return
        val parameter = Parameter(
            name = uniqueParameterName(componentId, propName),
            type = typeName,
            default = node.props[propName] ?: def.default,
        )
        execute(promoteToParameter(componentId, nodeId, propName, parameter), selectAfter = selectedId)
    }

    /**
     * The [ComponentDef] a `vforge.userComponent` instance [node] references, or null when [node] is not
     * an instance or its id no longer resolves. Drives the inspector's per-instance argument editors:
     * the parameters to edit come from the referenced definition (ADR-028).
     */
    fun componentOfInstance(node: Node): ComponentDef? {
        if (node.type != UserComponent.TYPE) return null
        val id = UserComponent.componentIdOf(node) ?: return null
        return document.components.firstOrNull { it.id == id }
    }

    /** [base] if free within component [componentId], else `base2`, `base3`, … — parameter names are unique per component. */
    private fun uniqueParameterName(componentId: String, base: String): String {
        val taken = document.components.firstOrNull { it.id == componentId }
            ?.parameters?.mapTo(HashSet()) { it.name } ?: emptySet()
        if (base !in taken) return base
        var n = 2
        while ("$base$n" in taken) n++
        return "$base$n"
    }

    /** Set (or clear) a node's name (T3). */
    fun renameNode(id: NodeId, name: String?) {
        val rootId = activeEditRootId ?: return
        execute(RenameNode(rootId, id, name), selectAfter = selectedId)
    }

    /** Request an inline rename of the current selection (F2); the tree panel picks this up. No-op if nothing is selected. */
    fun requestRenameSelected() {
        renameRequest = selectedId
    }

    /** Clear a consumed rename request (called by the tree once it has entered rename mode). */
    fun clearRenameRequest() {
        renameRequest = null
    }

    /** Toggle a node's hidden flag (removes it from render and codegen, DATA_MODEL §5). */
    fun toggleHidden(id: NodeId) {
        val root = activeEditRoot ?: return
        val rootId = activeEditRootId ?: return
        val node = root.findById(id) ?: return
        execute(SetNodeFlags(rootId, id, hidden = !node.hidden), selectAfter = selectedId)
    }

    /** Toggle a node's locked flag; locking the current selection also clears it (locked ⇒ unselectable). */
    fun toggleLocked(id: NodeId) {
        val root = activeEditRoot ?: return
        val rootId = activeEditRootId ?: return
        val node = root.findById(id) ?: return
        val nowLocked = !node.locked
        val keep = if (nowLocked && selectedId == id) null else selectedId
        execute(SetNodeFlags(rootId, id, locked = nowLocked), selectAfter = keep)
    }

    /** Copy the whole selection to the clipboard (D5, C10). */
    fun copySelected() {
        clipboard = selectionTopLevel()
    }

    /** Cut the whole selection: copy it, then delete it in one undoable step. */
    fun cut() {
        val targets = selectionTopLevel()
        if (targets.isEmpty()) return
        clipboard = targets
        deleteSelected()
    }

    /**
     * Paste fresh-id clones of the clipboard at the current insertion point (D5: targets selection) in one
     * step, selecting the clones. Clipboard entries that would form a cycle are skipped (#70); the rest
     * paste in clipboard order.
     */
    fun paste() {
        val rootId = activeEditRootId ?: return
        val pasteable = clipboard.filter { !wouldInsertingCycle(it) }
        if (pasteable.isEmpty()) return
        val base = insertionAddress() ?: return
        val clones = pasteable.map { it.withFreshIds() }
        val command = if (clones.size == 1) {
            AddNode(rootId, base, clones[0])
        } else {
            // base.index + k keeps the clones in order: each later insert lands right after the previous.
            CompositeCommand(
                clones.mapIndexed { k, clone -> AddNode(rootId, base.copy(index = base.index + k), clone) },
                label = "Paste ${clones.size} nodes",
            )
        }
        executeSelectingAll(command, clones.map { it.id })
    }

    /** Move [id] to [target] (reorder or reparent), if the drop is legal; keeps the node selected. */
    fun moveNode(id: NodeId, target: ChildAddress) {
        val rootId = activeEditRootId ?: return
        if (!canDrop(id, target)) return
        execute(MoveNode(rootId, id, target), selectAfter = id)
    }

    // --- property & modifier editing (M5) ---------------------------------------------------------

    /** Set (or clear, when [value] is null) a node prop; live-updates the canvas. Coalesces per prop (D3). */
    fun setProp(nodeId: NodeId, key: String, value: PropValue?) {
        val rootId = activeEditRootId ?: return
        execute(SetProp(rootId, nodeId, key, value), selectAfter = selectedId)
    }

    /** Reset a prop to its schema default (I7) — removes it when the default is absent. */
    fun resetProp(nodeId: NodeId, def: PropDefinition) {
        setProp(nodeId, def.name, def.default)
    }

    /**
     * The selected nodes that share the primary's type — the set a **shared** inspector edit applies to
     * (C10). Same-type only, so the primary's data-driven [PropDefinition]s are valid for every target.
     * Empty with no selection; a single-element list for a lone selection.
     */
    fun sameTypeSelection(): List<Node> {
        val primary = selectedNode ?: return emptyList()
        return selectedNodes.filter { it.type == primary.type }
    }

    /**
     * Set a prop on every same-type selected node as one undoable step (C10 shared edit). With a single
     * selection this is exactly [setProp] on the primary. A continuous edit (slider/stepper) coalesces
     * into one history entry per (prop, target set), like single-node editing does. Keeps the selection.
     */
    fun setPropShared(key: String, value: PropValue?) {
        val rootId = activeEditRootId ?: return
        val targets = sameTypeSelection()
        when {
            targets.isEmpty() -> return
            targets.size == 1 -> execute(SetProp(rootId, targets[0].id, key, value), selectAfter = selectedId)
            else -> {
                val command = CompositeCommand(
                    targets.map { SetProp(rootId, it.id, key, value) },
                    label = "Set $key on ${targets.size} nodes",
                    coalesceKey = Triple("sharedProp", key, targets.map { it.id.value }.sorted()),
                )
                executeSelectingAll(command, selectedIds)
            }
        }
    }

    /**
     * Append a modifier of [type] (with its schema defaults) to a node's chain. Ids are freshly
     * generated; order is preserved, new entry last (the user reorders via drag).
     */
    fun addModifier(nodeId: NodeId, type: String) {
        val root = activeEditRoot ?: return
        val rootId = activeEditRootId ?: return
        val node = root.findById(nodeId) ?: return
        val def = catalog.modifierDef(type) ?: return
        val args = def.args.mapNotNull { arg -> arg.default?.let { arg.name to it } }.toMap()
        val entry = ModifierEntry(id = Ulid.next(), type = type, args = args)
        execute(SetModifiers(rootId, nodeId, node.modifiers + entry), selectAfter = selectedId)
    }

    /** Remove the modifier [modifierId] from a node's chain. */
    fun removeModifier(nodeId: NodeId, modifierId: String) {
        val root = activeEditRoot ?: return
        val rootId = activeEditRootId ?: return
        val node = root.findById(nodeId) ?: return
        execute(
            SetModifiers(
                rootId,
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
        val root = activeEditRoot ?: return
        val rootId = activeEditRootId ?: return
        val node = root.findById(nodeId) ?: return
        val updated = node.modifiers.map { if (it.id == modifierId) it.copy(enabled = !it.enabled) else it }
        execute(SetModifiers(rootId, nodeId, updated), selectAfter = selectedId)
    }

    /**
     * Reorder a node's modifier chain, moving the entry at [from] to index [to]. Order is semantic
     * (ADR-005), so this is a real edit, not cosmetic. Out-of-range indices are ignored.
     */
    fun moveModifier(nodeId: NodeId, from: Int, to: Int) {
        val root = activeEditRoot ?: return
        val rootId = activeEditRootId ?: return
        val node = root.findById(nodeId) ?: return
        val list = node.modifiers
        if (from !in list.indices || to !in list.indices || from == to) return
        val reordered = list.toMutableList().apply { add(to, removeAt(from)) }
        execute(SetModifiers(rootId, nodeId, reordered), selectAfter = selectedId)
    }

    /** Set (or clear) one arg of a modifier; live-updates and coalesces per arg (D3). */
    fun setModifierArg(nodeId: NodeId, modifierId: String, key: String, value: PropValue?) {
        val rootId = activeEditRootId ?: return
        execute(SetModifierArg(rootId, nodeId, modifierId, key, value), selectAfter = selectedId)
    }

    // --- theme editing (M8) -----------------------------------------------------------------------

    /** The project theme the editor edits and the canvas renders (H1). */
    val theme: Theme get() = document.theme

    /** Toggle the canvas between the theme's light and dark values (H2). View state, not an edit. */
    fun toggleCanvasDark() {
        canvasDark = !canvasDark
    }

    /** Toggle the debug container-border overlay (#117). Editor view state, not an edit. */
    fun toggleShowBorders() {
        showBorders = !showBorders
    }

    /** Toggle the static alignment-guide overlay (C11, #118). Editor view state, not an edit. */
    fun toggleShowGuides() {
        showGuides = !showGuides
    }

    /** Toggle interactive preview / run mode (C13, #120). Editor view state, not an edit. */
    fun toggleInteractivePreview() {
        interactivePreview = !interactivePreview
    }

    /** Toggle the editor chrome between light and dark (S3, #104). View state, persisted; not an edit. */
    fun toggleChromeDark() {
        chromeDark = !chromeDark
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

    /** Grow/shrink the palette by [delta] dp (splitter drag), clamped to the panel width bounds. */
    fun resizePalette(delta: Float) {
        paletteWidth = PanelLayout.clampWidth(paletteWidth + delta)
    }

    /** Grow/shrink the tree panel by [delta] dp (splitter drag), clamped. */
    fun resizeTree(delta: Float) {
        treeWidth = PanelLayout.clampWidth(treeWidth + delta)
    }

    /** Grow/shrink the inspector by [delta] dp (splitter drag), clamped. */
    fun resizeInspector(delta: Float) {
        inspectorWidth = PanelLayout.clampWidth(inspectorWidth + delta)
    }

    /** Show/hide the code-preview panel (G3, #50). */
    fun toggleCodePreview() {
        codePreviewVisible = !codePreviewVisible
    }

    /** Toggle soft-wrapping in the code-preview panel (#115). Persisted via the panel layout. */
    fun toggleCodePreviewWrap() {
        codePreviewWrap = !codePreviewWrap
    }

    /** Grow/shrink the code-preview panel by [delta] dp (splitter drag), clamped to its own wider bound. */
    fun resizeCodePreview(delta: Float) {
        codePreviewWidth = PanelLayout.clampCodePreviewWidth(codePreviewWidth + delta)
    }

    /**
     * Restore the persisted panel layout at startup (S1, #43). Widths are re-clamped defensively so a
     * hand-edited or differently-versioned prefs file can never wedge a panel off-screen; visibility is
     * taken as-is. This is view state, not an edit — it never touches the document or history.
     */
    fun applyLayout(layout: PanelLayout) {
        paletteVisible = layout.paletteVisible
        treeVisible = layout.treeVisible
        inspectorVisible = layout.inspectorVisible
        codePreviewVisible = layout.codePreviewVisible
        paletteWidth = PanelLayout.clampWidth(layout.paletteWidth)
        treeWidth = PanelLayout.clampWidth(layout.treeWidth)
        inspectorWidth = PanelLayout.clampWidth(layout.inspectorWidth)
        codePreviewWidth = PanelLayout.clampCodePreviewWidth(layout.codePreviewWidth)
        codePreviewWrap = layout.codePreviewWrap
    }

    /** Snapshot the current panel layout for persistence. */
    fun panelLayout(): PanelLayout = PanelLayout(
        paletteVisible = paletteVisible,
        treeVisible = treeVisible,
        inspectorVisible = inspectorVisible,
        codePreviewVisible = codePreviewVisible,
        paletteWidth = paletteWidth,
        treeWidth = treeWidth,
        inspectorWidth = inspectorWidth,
        codePreviewWidth = codePreviewWidth,
        codePreviewWrap = codePreviewWrap,
    )

    // --- recent projects (D8) ---------------------------------------------------------------------

    /** Restore the persisted recent-projects list at startup (D8); view state, re-sanitized defensively. */
    fun applyRecentProjects(paths: List<String>) {
        recentProjects = RecentProjects.sanitized(paths)
    }

    /** Record [path] as the most-recent project (D8): promoted to the front, de-duplicated, capped. */
    fun noteRecentProject(path: String) {
        recentProjects = RecentProjects.updated(recentProjects, path)
    }

    /** Drop [path] from the recent list — e.g. it failed to open because the file is gone. */
    fun removeRecentProject(path: String) {
        recentProjects = recentProjects.filterNot { it == path }
    }

    /** Clear the recent-projects list (File → Open Recent → Clear Recent). */
    fun clearRecentProjects() {
        recentProjects = emptyList()
    }

    // --- palette favorites & recents (P5a, #121) --------------------------------------------------

    /** Restore the persisted favorites list at startup (P5a); per-user chrome, re-sanitized defensively. */
    fun applyFavoriteComponents(keys: List<String>) {
        favoriteComponents = FavoriteComponents.sanitized(keys)
    }

    /** Whether [entry] is currently pinned as a favorite (P5a). */
    fun isFavorite(entry: PaletteEntry): Boolean = entry.key in favoriteComponents

    /** Pin or unpin [entry] as a favorite (P5a); the shell persists the updated list. */
    fun toggleFavorite(entry: PaletteEntry) {
        favoriteComponents = FavoriteComponents.toggled(favoriteComponents, entry.key)
    }

    /** Record a just-inserted entry [key] as most-recently-used (P5a): to the front, de-duplicated, capped. */
    private fun noteRecentComponent(key: String) {
        recentComponents = (listOf(key) + recentComponents.filterNot { it == key }).take(MAX_RECENT_COMPONENTS)
    }

    /** The pinned entries resolved against the live [palette], in starring order; stale keys drop out (P5a). */
    val favoriteEntries: List<PaletteEntry>
        get() = resolvePaletteKeys(favoriteComponents)

    /** The recently-used entries resolved against the live [palette], most-recent first; stale keys drop out (P5a). */
    val recentEntries: List<PaletteEntry>
        get() = resolvePaletteKeys(recentComponents)

    /** Map palette [keys] back to their live [PaletteEntry], preserving [keys] order and dropping any that no longer exist. */
    private fun resolvePaletteKeys(keys: List<String>): List<PaletteEntry> {
        val byKey = palette.associateBy { it.key }
        return keys.mapNotNull { byKey[it] }
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
     * Fit the active screen's device frame within an [availW]×[availH] canvas area (px) at [density]
     * (C6, #59): resolves the frame's dp size against [activeDeviceProfile] and sets the [viewport] to
     * the clamped fit zoom, centred. Preview-only and not undoable, like the other viewport moves. The
     * canvas calls this on a profile change; the no-arg overload backs the View → Fit menu item.
     */
    fun fitToFrame(availW: Float, availH: Float, density: Float) {
        val profile = activeDeviceProfile
        viewport = viewport.fittedTo(availW, availH, profile.width * density, profile.height * density)
    }

    /** Fit to the frame using the canvas's [last-measured area][canvasFitBounds]; a no-op until measured. */
    fun fitToFrame() {
        val bounds = canvasFitBounds ?: return
        fitToFrame(bounds.availW, bounds.availH, bounds.density)
    }

    /** Whether a Fit action can run (View menu / Ctrl+9): there is something to frame and a measured area. */
    val canFitToFrame: Boolean
        get() = activeEditRoot != null && canvasFitBounds != null

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
        val root = activeEditRoot ?: return false
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
     * root. Null only if there is no active edit surface.
     */
    private fun insertionAddress(): ChildAddress? {
        val root = activeEditRoot ?: return null
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

    companion object {
        /** The palette category the document's user components cluster under (P6a). */
        const val USER_COMPONENTS_CATEGORY = "Components"

        /** How many recently-used palette entries to keep for the palette's "Recent" quick-access row (P5a, #121). */
        const val MAX_RECENT_COMPONENTS = 8
    }
}

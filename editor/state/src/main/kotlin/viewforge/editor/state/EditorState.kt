package viewforge.editor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.Screen
import viewforge.model.findById

/**
 * The editor's document session, exposed as Compose state so the canvas recomposes automatically
 * (ARCHITECTURE §5).
 *
 * M2 is a *static* canvas: the [project] is loaded once and only the active screen selection is
 * observable. Mutation goes through commands and undo/redo (ARCHITECTURE §5, CLAUDE.md rule 3) —
 * that arrives at M4, and this class is the seam it will grow from. It stays deliberately minimal
 * until then rather than sketching an API M4 would have to rework.
 */
class EditorState(val project: Project) {
    /** Which screen the canvas shows. Observable now so the screen switcher (D6) can drive it later. */
    var activeScreenId: String? by mutableStateOf(project.screens.firstOrNull()?.id)

    val activeScreen: Screen?
        get() = project.screens.firstOrNull { it.id == activeScreenId } ?: project.screens.firstOrNull()

    /**
     * The currently selected node's id, or null for no selection (M3). Shared, observable state so
     * canvas and tree panel stay in sync bidirectionally (FEATURES T1): both read it to highlight and
     * both call [select] to change it. Selection is transient view state, not part of the document —
     * it lives here, never in the IR.
     */
    var selectedId: NodeId? by mutableStateOf(null)
        private set

    /** Select a node by id, or pass null to clear selection (e.g. a click on empty canvas). */
    fun select(id: NodeId?) {
        selectedId = id
    }

    /** The selected [Node] resolved against the active screen, or null if nothing valid is selected. */
    val selectedNode: Node?
        get() {
            val id = selectedId ?: return null
            return activeScreen?.root?.findById(id)
        }
}

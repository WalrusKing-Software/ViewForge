package viewforge.editor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import viewforge.model.Project
import viewforge.model.Screen

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
}

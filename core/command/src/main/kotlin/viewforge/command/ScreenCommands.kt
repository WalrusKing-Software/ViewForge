package viewforge.command

import viewforge.model.Project
import viewforge.model.Screen
import viewforge.model.StateField

/**
 * Screen-level commands (D6). A [Screen] is a top-level document entry, not part of a node tree, so
 * these transform [Project.screens] directly — beside [ThemeCommands][SetTheme], which likewise edit a
 * top-level field. Like every mutation they flow through [Command] (CLAUDE.md rule 3), so adding,
 * removing and renaming screens are all undoable.
 *
 * Screen *names* feed the generated composable/file name (GC-3); they are validated at edit time by
 * the editor and, as a last line of defence, by codegen. These commands do not re-validate — they are
 * the mechanical mutation — so the editor must gate an illegal name before it ever reaches one.
 */

/** Rename screen [id] to [name]. Rename never affects codegen *structure*, only the emitted fn/file name. */
data class RenameScreen(val id: String, val name: String, override val label: String = "Rename screen") : Command {
    override fun apply(doc: Project): Project {
        val screens = doc.screens.map { if (it.id == id) it.copy(name = name) else it }
        return doc.copy(screens = screens)
    }

    override fun invert(doc: Project): Command {
        val old = doc.screens.firstOrNull { it.id == id } ?: return NoOp
        return RenameScreen(id, old.name)
    }
}

/**
 * Set screen [id]'s device preview profile to [profileId] (C6) — the canvas viewport the screen is
 * framed to (`Screen.previewProfile`). A preview-only concern: it never affects codegen. [profileId] is
 * nullable so the inverse can restore a screen that had none exactly, keeping undo precise. Absent id ⇒
 * a no-op inverse, like the other screen commands.
 */
data class SetPreviewProfile(val id: String, val profileId: String?, override val label: String = "Set preview size") :
    Command {
    override fun apply(doc: Project): Project =
        doc.copy(screens = doc.screens.map { if (it.id == id) it.copy(previewProfile = profileId) else it })

    override fun invert(doc: Project): Command {
        val old = doc.screens.firstOrNull { it.id == id } ?: return NoOp
        return SetPreviewProfile(id, old.previewProfile)
    }
}

/**
 * Insert [screen] at [index] in the screen list. The inverse removes it again. [index] is clamped into
 * range on apply so an out-of-range request appends rather than throwing.
 */
data class AddScreen(val screen: Screen, val index: Int, override val label: String = "Add screen") : Command {
    override fun apply(doc: Project): Project {
        val at = index.coerceIn(0, doc.screens.size)
        val screens = doc.screens.toMutableList().apply { add(at, screen) }
        return doc.copy(screens = screens)
    }

    override fun invert(doc: Project): Command = RemoveScreen(screen.id)
}

/**
 * Remove screen [id]. The inverse restores it to its exact position, so [invert] reads the screen and
 * its index out of the pre-apply document (like [RemoveNode]). Removing the **last** screen is a no-op:
 * a project always keeps at least one screen to edit and export.
 */
data class RemoveScreen(val id: String, override val label: String = "Delete screen") : Command {
    override fun apply(doc: Project): Project {
        if (doc.screens.size <= 1) return doc
        return doc.copy(screens = doc.screens.filterNot { it.id == id })
    }

    override fun invert(doc: Project): Command {
        val index = doc.screens.indexOfFirst { it.id == id }
        val screen = doc.screens.getOrNull(index)
        // If the screen isn't present, or it's the last one, apply() is a no-op; a no-op inverse keeps
        // History consistent.
        return if (screen != null && doc.screens.size > 1) AddScreen(screen, index) else NoOp
    }
}

/**
 * Read-only **screen state** commands (ADR-034, #21). A screen's [Screen.state] is a top-level list of
 * declared data fields the UI binds to; like the other screen commands these transform the screen entry
 * directly and flow through [Command] so declaring, editing, and removing a field are undoable
 * (CLAUDE.md rule 3). Field *names* are binding roots (legal identifiers, GC-3), validated by the editor
 * before a command is ever run — these are the mechanical mutation and do not re-validate.
 */

/** Append [field] to screen [screenId]'s state. Inverse removes it by name. A no-op if the screen is gone. */
data class AddStateField(val screenId: String, val field: StateField, override val label: String = "Add state") :
    Command {
    override fun apply(doc: Project): Project =
        doc.copy(screens = doc.screens.map { if (it.id == screenId) it.copy(state = it.state + field) else it })

    override fun invert(doc: Project): Command = RemoveStateField(screenId, field.name)
}

/**
 * Remove the state field named [name] from screen [screenId]. The inverse restores it to its exact
 * position, so [invert] reads the field and its index out of the pre-apply document (like [RemoveNode]).
 */
data class RemoveStateField(val screenId: String, val name: String, override val label: String = "Remove state") :
    Command {
    override fun apply(doc: Project): Project = doc.copy(
        screens = doc.screens.map { s ->
            if (s.id == screenId) s.copy(state = s.state.filterNot { it.name == name }) else s
        },
    )

    override fun invert(doc: Project): Command {
        val screen = doc.screens.firstOrNull { it.id == screenId }
        val index = screen?.state?.indexOfFirst { it.name == name } ?: -1
        val field = screen?.state?.getOrNull(index)
        // Absent field ⇒ apply() is a no-op; a no-op inverse keeps History consistent.
        return if (field != null) InsertStateField(screenId, index, field) else NoOp
    }
}

/**
 * Insert [field] at [index] in screen [screenId]'s state — the position-preserving inverse of
 * [RemoveStateField]. [index] is clamped so an out-of-range restore appends rather than throwing.
 */
data class InsertStateField(
    val screenId: String,
    val index: Int,
    val field: StateField,
    override val label: String = "Add state",
) : Command {
    override fun apply(doc: Project): Project = doc.copy(
        screens = doc.screens.map { s ->
            if (s.id != screenId) {
                s
            } else {
                s.copy(state = s.state.toMutableList().apply { add(index.coerceIn(0, size), field) })
            }
        },
    )

    override fun invert(doc: Project): Command = RemoveStateField(screenId, field.name)
}

/**
 * Replace the state field at [index] of screen [screenId] with [field] — the one command behind every
 * in-place edit (rename, type change, sample edit), since all of those are just a different [StateField].
 * Coalesces per (screen, index) so typing into a sample literal collapses to one undo step, like [SetProp].
 */
data class SetStateField(
    val screenId: String,
    val index: Int,
    val field: StateField,
    override val label: String = "Edit state",
) : Command {
    override val coalesceKey: Any = Triple(screenId, "state", index)

    override fun apply(doc: Project): Project = doc.copy(
        screens = doc.screens.map { s ->
            if (s.id != screenId || index !in s.state.indices) {
                s
            } else {
                s.copy(state = s.state.toMutableList().apply { this[index] = field })
            }
        },
    )

    override fun invert(doc: Project): Command {
        val old = doc.screens.firstOrNull { it.id == screenId }?.state?.getOrNull(index)
        return if (old != null) SetStateField(screenId, index, old, label) else NoOp
    }
}

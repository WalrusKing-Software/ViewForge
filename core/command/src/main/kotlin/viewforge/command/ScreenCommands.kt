package viewforge.command

import viewforge.model.Project
import viewforge.model.Screen

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

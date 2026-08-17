package viewforge.command

import viewforge.model.Project
import viewforge.model.Theme
import viewforge.model.canRenameToken
import viewforge.model.mapThemeRefs
import viewforge.model.renameToken

/**
 * Theme-editing commands (M8). The project [Theme] is a top-level field, not per-screen, so these
 * sit beside the M4/M5 node commands but transform [Project.theme] directly. Like every mutation they
 * flow through [Command] (CLAUDE.md rule 3) so theme edits are undoable and live-update the canvas.
 */

/**
 * Replace the project theme wholesale (H1/H4). The theme is a small structure, so a whole-value swap
 * is the simplest undoable edit; the inverse restores the prior theme, captured from the pre-apply
 * document. Continuous edits in the theme editor — scrubbing a hex field or a size stepper — collapse
 * into one history entry via [coalesceKey], exactly like [SetProp] (ADR-017): the key identifies the
 * specific token field being edited (e.g. `Triple("colors", "primary", "light")`).
 */
data class SetTheme(
    val theme: Theme,
    override val coalesceKey: Any? = null,
    override val label: String = "Edit theme",
) : Command {
    override fun apply(doc: Project): Project = if (doc.theme == theme) doc else doc.copy(theme = theme)

    override fun invert(doc: Project): Command = SetTheme(doc.theme, coalesceKey, label)
}

/**
 * Rename a theme token in [category] from [from] to [to], propagating the rename to every
 * [viewforge.model.PropValue.ThemeRef] that referenced it across all screens (H5, DATA_MODEL §8). The
 * whole edit — the theme map key and all references — is a single undoable command.
 *
 * A no-op (returns the document unchanged) when the rename is invalid (source absent, target already
 * present, or unchanged), so a rename never silently merges or clobbers a token. The inverse renames
 * back, computed against the pre-apply image so it mirrors that same validity.
 */
data class RenameThemeToken(
    val category: String,
    val from: String,
    val to: String,
    override val label: String = "Rename token",
) : Command {
    override fun apply(doc: Project): Project {
        val renamed = doc.theme.renameToken(category, from, to) ?: return doc
        val oldToken = "$category.$from"
        val newToken = "$category.$to"
        val screens = doc.screens.map { screen ->
            val newRoot = screen.root.mapThemeRefs { token -> if (token == oldToken) newToken else token }
            if (newRoot === screen.root) screen else screen.copy(root = newRoot)
        }
        return doc.copy(theme = renamed, screens = screens)
    }

    override fun invert(doc: Project): Command =
        if (doc.theme.canRenameToken(category, from, to)) RenameThemeToken(category, to, from, label) else NoOp
}

package viewforge.editor.shell

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import viewforge.editor.state.EditorState
import viewforge.project.LoadFailure
import viewforge.project.LoadResult
import viewforge.project.ProjectStore
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The in-editor `.vforge` document flow (D1, issue #37): New / Open / Save / Save As. Hoisted into a
 * controller — remembered once in the shell — exactly like [ExportController], so the File menu and the
 * keyboard shortcuts drive one flow and the menu stays a thin view.
 *
 * Unlike export, persistence names **no** framework package: it reads and writes through
 * [ProjectStore] in `core/project`, a Compose-free core module (all writes atomic and guarded, CLAUDE.md
 * rule 6). The controller owns only the transient UI around it — the native file dialogs, the
 * discard-unsaved prompt, and an error dialog — and drives the document swap through [EditorState].
 *
 * Runs synchronously on the UI thread: a `.vforge` file is small, so the load/save block is
 * unnoticeable (the same trade-off [ExportController] documents).
 */
internal class DocumentController(private val state: EditorState, private val prefs: PreferencesController) {
    /** A pending New/Open held behind the discard-unsaved confirmation, or null when nothing is waiting. */
    var pendingDiscard: PendingDiscard? by mutableStateOf(null)
        private set

    /** A load/save error to surface, or null. Success is silent — the unsaved-marker clearing is the cue. */
    var error: String? by mutableStateOf(null)
        private set

    /** File → New: a blank document, guarding any unsaved edits first. */
    fun newDocument() = guardUnsaved("Creating a new document") { state.newDocument() }

    /** File → Open: pick a `.vforge` file and load it, guarding any unsaved edits first. */
    fun open() = guardUnsaved("Opening another document") {
        val path = chooseOpenFile() ?: return@guardUnsaved
        loadInto(path)
    }

    /**
     * File → Open Recent: load [pathString] straight off (no chooser), guarding unsaved edits (D8). A file
     * that no longer opens reports the error and is dropped from the recent list, so a stale entry cleans
     * itself up.
     */
    fun openRecent(pathString: String) = guardUnsaved("Opening another document") {
        loadInto(Path.of(pathString))
    }

    /** Load [path] into the editor: on success swap the document and record it recent; on failure surface
     * the reason and forget the path (a no-op for a path that was not a recent). */
    private fun loadInto(path: Path) {
        when (val result = ProjectStore.load(path)) {
            is LoadResult.Success -> {
                // A file loaded from an older schema was migrated in memory; the first save must back up
                // the original before overwriting it with the migrated form (D9).
                state.replaceDocument(
                    result.project,
                    path,
                    migratedFromOlderSchema = result.migratedFromVersion != null,
                )
                prefs.recordRecent(path)
            }
            is LoadResult.Failure -> {
                error = describe(result)
                prefs.forgetRecent(path.toString())
            }
        }
    }

    /** File → Save: write to the current file, or fall through to Save As when there isn't one yet. */
    fun save() {
        val path = state.currentPath ?: return saveAs()
        writeTo(path)
    }

    /** File → Save As: pick a destination (forcing a `.vforge` extension) and write there. */
    fun saveAs() {
        val path = chooseSaveFile() ?: return
        writeTo(path)
    }

    fun confirmDiscard() {
        val proceed = pendingDiscard?.proceed
        pendingDiscard = null
        proceed?.invoke()
    }

    fun dismissDiscard() {
        pendingDiscard = null
    }

    fun dismissError() {
        error = null
    }

    /** Run [action] now, unless there are unsaved edits — then stash it behind the discard prompt. */
    private fun guardUnsaved(what: String, action: () -> Unit) {
        if (state.isDirty) pendingDiscard = PendingDiscard(what, action) else action()
    }

    private fun writeTo(path: Path) {
        // Back up the file we're about to replace when the document was migrated from an older schema, so
        // the original is never lost to the migrated overwrite (D9). GuardedWriter no-ops the backup when
        // the target doesn't exist yet (a Save As to a fresh path).
        runCatching { ProjectStore.save(state.document, path, backup = state.backupOnNextSave) }.fold(
            onSuccess = {
                state.markSaved(path)
                prefs.recordRecent(path)
            },
            onFailure = { e -> error = "Could not save to $path: ${e.message}" },
        )
    }

    /** A human-readable line for each [LoadFailure], so a bad file reports *why* rather than crashing. */
    private fun describe(failure: LoadResult.Failure): String {
        val reason = when (failure.kind) {
            LoadFailure.FILE_TOO_LARGE -> "The file is too large to open."
            LoadFailure.IO_ERROR -> "The file could not be read."
            LoadFailure.MALFORMED -> "The file is not a valid .vforge document."
            LoadFailure.MISSING_VERSION -> "The file is missing its schema version."
            LoadFailure.NEWER_SCHEMA -> "The file was made by a newer version of ViewForge."
            LoadFailure.MIGRATION_FAILED -> "The file could not be upgraded to the current format."
            LoadFailure.VALIDATION_FAILED -> "The file failed a safety check."
        }
        return "$reason\n\n${failure.detail}"
    }
}

/** A New/Open deferred until the user resolves unsaved changes; [what] labels it in the prompt. */
internal data class PendingDiscard(val what: String, val proceed: () -> Unit)

@Composable
internal fun rememberDocumentController(state: EditorState, prefs: PreferencesController): DocumentController =
    remember(state, prefs) { DocumentController(state, prefs) }

/** The discard-unsaved and load/save-error dialogs, rendered once at the shell root (like [ExportDialogs]). */
@Composable
internal fun DocumentDialogs(controller: DocumentController) {
    controller.pendingDiscard?.let { pending ->
        AlertDialog(
            onDismissRequest = controller::dismissDiscard,
            title = { Text("Discard unsaved changes?") },
            text = { Text("${pending.what} will discard your unsaved changes. This can't be undone.") },
            confirmButton = { TextButton(onClick = controller::confirmDiscard) { Text("Discard") } },
            dismissButton = { TextButton(onClick = controller::dismissDiscard) { Text("Cancel") } },
        )
    }

    controller.error?.let { message ->
        AlertDialog(
            onDismissRequest = controller::dismissError,
            title = { Text("Couldn't open the file") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = controller::dismissError) { Text("OK") } },
        )
    }
}

/**
 * The save-on-close prompt (#56): shown when the user tries to close the window with unsaved edits.
 * Save writes through the existing [DocumentController] flow (which opens a Save As dialog for a
 * never-saved document) and exits **only if the save actually landed** — a cancelled Save As or a write
 * error leaves the document dirty, so the close is aborted rather than losing work. Discard exits without
 * saving; crash recovery (#54) is the real safety net, this is only the clean-exit UX. Cancel — or
 * dismissing the dialog — aborts the close.
 *
 * Rendered once at the shell root like [DocumentDialogs]; [visible] is driven by the window's
 * `onCloseRequest` in `:app`, which raises it only when the document is dirty.
 */
@Composable
internal fun ExitConfirmation(
    visible: Boolean,
    state: EditorState,
    document: DocumentController,
    onExit: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onCancel, // an away-click aborts the close — the safe direction, never data loss
        title = { Text("Save changes before closing?") },
        text = { Text("“${state.document.name}” has unsaved changes. Save them before ViewForge closes?") },
        confirmButton = {
            TextButton(onClick = {
                document.save()
                // Exit only if the save landed; a cancelled Save As or a write error keeps it dirty, so stay.
                if (state.isDirty) onCancel() else onExit()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onExit) { Text("Discard") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

private const val VFORGE_EXTENSION = "vforge"

private fun vforgeChooser(title: String): JFileChooser = JFileChooser().apply {
    dialogTitle = title
    fileSelectionMode = JFileChooser.FILES_ONLY
    isAcceptAllFileFilterUsed = false
    fileFilter = FileNameExtensionFilter("ViewForge document (*.$VFORGE_EXTENSION)", VFORGE_EXTENSION)
}

/** Native open dialog, restricted to `.vforge`. Returns null when the user cancels. */
private fun chooseOpenFile(): Path? {
    val chooser = vforgeChooser("Open ViewForge document")
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()
    } else {
        null
    }
}

/** Native save dialog; forces a `.vforge` extension so a typed name without one still round-trips. */
private fun chooseSaveFile(): Path? {
    val chooser = vforgeChooser("Save ViewForge document")
    return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()?.let(::withVforgeExtension)
    } else {
        null
    }
}

/** Append `.vforge` unless the chosen name already carries it (case-insensitive). */
private fun withVforgeExtension(path: Path): Path {
    val name = path.fileName.toString()
    return if (name.substringAfterLast('.', "").equals(VFORGE_EXTENSION, ignoreCase = true)) {
        path
    } else {
        path.resolveSibling("$name.$VFORGE_EXTENSION")
    }
}

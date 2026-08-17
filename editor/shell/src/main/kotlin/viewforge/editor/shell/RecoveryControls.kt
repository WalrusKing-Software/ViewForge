package viewforge.editor.shell

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import viewforge.editor.state.EditorState
import viewforge.project.RecoverySnapshot
import viewforge.project.RecoveryStore
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Autosave + crash recovery (D4). Remembered once in the shell, like [DocumentController]: a timer calls
 * [tick] on an interval, and — at launch — any [pending] snapshot loaded from disk drives a restore
 * prompt. It names no framework package: it reads and writes through [RecoveryStore] in `core/project`
 * (the same no-seam precedent [DocumentController] uses), against the per-user config [dir] the wiring
 * site supplies.
 *
 * The cadence is not held here: the shell's timer reads the live [EditorState.autosaveIntervalSeconds]
 * preference (S5, #105) so a change takes effect without a restart. This controller only decides *what*
 * a tick does.
 *
 * The guarantee is *never lose work*: while unsaved edits exist a snapshot is written; it is cleared
 * only on a clean state (after a real Save) or an explicit discard — so a crash **or** a
 * quit-without-saving leaves the snapshot to be offered next launch. Writes are atomic (the guarded
 * writer), so a crash mid-snapshot cannot corrupt it.
 */
internal class RecoveryController(
    private val state: EditorState,
    private val dir: Path,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * A snapshot recovered from a previous session, awaiting the user's Restore/Discard choice; null once
     * resolved (or when there was nothing to recover). Loaded once, at construction — the first frame.
     */
    var pending: RecoverySnapshot? by mutableStateOf(RecoveryStore.load(dir))
        private set

    /**
     * One autosave tick. While a recovered snapshot is still [pending] it does nothing — the freshly
     * loaded (clean) document must not overwrite or clear the very recovery the user hasn't answered yet.
     * Otherwise: snapshot the document when there are unsaved edits, or clear the sidecar once clean (so a
     * saved document leaves no stale restore offer). Best-effort — a failure to autosave must never
     * interrupt editing.
     */
    fun tick() {
        if (pending != null) return
        if (state.isDirty) {
            runCatching {
                RecoveryStore.save(
                    RecoverySnapshot(
                        originalPath = state.currentPath?.toString(),
                        savedAt = now(),
                        document = state.document,
                    ),
                    dir,
                )
            }
        } else {
            RecoveryStore.clear(dir)
        }
    }

    /**
     * Clear the recovery sidecar the instant the document is clean — e.g. right after a Save — instead of
     * waiting for the next autosave [tick]. Save-and-quit calls `exitApplication()` before the timer fires
     * again, so without this a cleanly-saved document still offered a stale "restore unsaved work?" prompt
     * on the next launch (#189). A no-op while a recovered snapshot is still [pending] (the freshly-loaded
     * launch document is clean but must not clear the very recovery the user has not answered) or while the
     * document is dirty (that snapshot is real unsaved work to keep).
     */
    fun clearIfClean() {
        if (pending == null && !state.isDirty) RecoveryStore.clear(dir)
    }

    /** Restore the recovered document into the editor (marked dirty — it is ahead of what is on disk). */
    fun restore() {
        val snap = pending ?: return
        pending = null
        state.restoreRecovered(snap.document, snap.originalPath?.let { Path.of(it) })
    }

    /** Throw the recovered snapshot away and delete the sidecar, keeping the current document. */
    fun discard() {
        pending = null
        RecoveryStore.clear(dir)
    }
}

@Composable
internal fun rememberRecoveryController(state: EditorState, dir: Path): RecoveryController =
    remember(state, dir) { RecoveryController(state, dir) }

private val RECOVERED_AT_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

/** The launch-time "restore unsaved work?" prompt (D4), rendered once at the shell root like [DocumentDialogs]. */
@Composable
internal fun RecoveryDialog(controller: RecoveryController) {
    controller.pending?.let { snap ->
        val where = snap.originalPath?.let { "\n\nDocument: $it" } ?: "\n\nThis document was never saved."
        val when0 = RECOVERED_AT_FORMAT.format(Instant.ofEpochMilli(snap.savedAt))
        AlertDialog(
            onDismissRequest = {}, // A recovery must be answered, not dismissed by a stray click — no data loss.
            title = { Text("Restore unsaved work?") },
            text = { Text("ViewForge found autosaved changes from $when0.$where") },
            confirmButton = { TextButton(onClick = controller::restore) { Text("Restore") } },
            dismissButton = { TextButton(onClick = controller::discard) { Text("Discard") } },
        )
    }
}

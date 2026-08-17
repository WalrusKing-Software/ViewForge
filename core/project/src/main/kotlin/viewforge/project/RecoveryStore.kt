@file:OptIn(ExperimentalSerializationApi::class)

package viewforge.project

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import viewforge.model.Project
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * A point-in-time autosave of the working document, written to a sidecar so a crash — or a
 * quit-without-saving — is recoverable (D4). It wraps the whole [document] plus enough context to
 * restore it: [originalPath] (null for a never-saved document) so a restore knows where the file
 * belongs, and [savedAt] (epoch millis) for a human-readable "recovered from …" cue.
 *
 * Its own [recoveryVersion] is independent of the `.vforge` [Project.schemaVersion]: the recovery file
 * is a separate, private format, versioned so a future change stays forward-tolerant. Always emitted
 * (the file must be self-describing) even though [VforgeJson] omits defaults.
 */
@Serializable
data class RecoverySnapshot(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val recoveryVersion: Int = RECOVERY_VERSION,
    val originalPath: String? = null,
    val savedAt: Long,
    val document: Project,
) {
    companion object {
        const val RECOVERY_VERSION = 1
    }
}

/**
 * Reads and writes the crash-recovery sidecar (D4). Writes go through the same [GuardedWriter] as
 * project saves (atomic, CLAUDE.md rule 6), confined to [dir] — the per-user config directory the
 * caller supplies (`ConfigDir.resolve()`), taken as a parameter so `core/project` stays free of
 * `core/prefs`. A single file holds one pending snapshot; a never-saved document is covered too, since
 * the sidecar lives in the config dir rather than beside a (non-existent) project file.
 *
 * **Loading never fails the editor.** Like [viewforge.prefs.PreferencesStore] — and unlike
 * [ProjectStore], which reports *why* a real document failed — a missing, unreadable, or corrupt
 * recovery file yields `null` (no pending recovery) rather than an error that could block launch. The
 * recovery is a safety net; a broken net must never stop the app from starting.
 */
object RecoveryStore {
    const val FILE_NAME = "recovery.json"

    /** Atomically write [snapshot] to [dir]/[FILE_NAME], creating [dir] if needed. */
    fun save(snapshot: RecoverySnapshot, dir: Path) {
        val text = VforgeJson.encodeToString(RecoverySnapshot.serializer(), snapshot)
        GuardedWriter.write(dir.resolve(FILE_NAME), text, root = dir)
    }

    /** The pending recovery snapshot in [dir], or null when absent or unreadable (never throws). */
    fun load(dir: Path): RecoverySnapshot? {
        val file = dir.resolve(FILE_NAME)
        if (!Files.exists(file)) return null
        return try {
            val text = Files.readString(file, StandardCharsets.UTF_8)
            VforgeJson.decodeFromString(RecoverySnapshot.serializer(), text)
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            // kotlinx.serialization can surface a malformed document as IllegalArgumentException.
            null
        }
    }

    /** Delete the recovery sidecar in [dir] (after a clean save or a discarded restore). Best-effort. */
    fun clear(dir: Path) {
        runCatching { Files.deleteIfExists(dir.resolve(FILE_NAME)) }
    }
}

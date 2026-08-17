@file:OptIn(ExperimentalSerializationApi::class)

package viewforge.prefs

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import viewforge.project.GuardedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `Json` configuration for the editor-preferences file. Separate from `VforgeJson` (which carries
 * document-specific choices like the `PropValue` discriminator): prefs are a plain, human-editable
 * config file.
 *
 * - `encodeDefaults = true` — write every field, so `preferences.json` is complete and self-describing
 *   even at defaults (a user peeking at it sees the full shape).
 * - `ignoreUnknownKeys = true` — forward tolerance: a newer build may add a field, and an older build
 *   must still read the file rather than discard the user's whole layout.
 */
val PrefsJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Reads and writes the editor-preferences file (ADR-023). Writes go through the same [GuardedWriter]
 * as project saves (CLAUDE.md rule 6, atomic), confined to the [ConfigDir].
 *
 * **Preferences are non-critical chrome, so loading never fails the editor.** A missing, unreadable, or
 * corrupt file yields defaults — exactly a fresh install — rather than an error the user must dismiss.
 * This is the deliberate counterpart to document loading (`ProjectStore`), which reports *why* it failed
 * because a project *is* the user's work; losing a remembered panel width is not.
 */
object PreferencesStore {
    const val FILE_NAME = "preferences.json"

    /** Load preferences from [dir], or return defaults if the file is absent or cannot be read/parsed. */
    fun load(dir: Path = ConfigDir.resolve()): EditorPreferences {
        val file = dir.resolve(FILE_NAME)
        if (!Files.exists(file)) return EditorPreferences()
        return try {
            val text = Files.readString(file, StandardCharsets.UTF_8)
            PrefsJson.decodeFromString(EditorPreferences.serializer(), text).sanitized()
        } catch (_: IOException) {
            EditorPreferences()
        } catch (_: SerializationException) {
            EditorPreferences()
        } catch (_: IllegalArgumentException) {
            // kotlinx.serialization can surface a malformed document as IllegalArgumentException.
            EditorPreferences()
        }
    }

    /** Atomically write [prefs] to [dir]/[FILE_NAME] through the guarded writer, creating [dir] if needed. */
    fun save(prefs: EditorPreferences, dir: Path = ConfigDir.resolve()) {
        val text = PrefsJson.encodeToString(EditorPreferences.serializer(), prefs)
        GuardedWriter.write(dir.resolve(FILE_NAME), text, root = dir)
    }
}

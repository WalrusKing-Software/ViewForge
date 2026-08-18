@file:OptIn(ExperimentalSerializationApi::class)

package viewforge.project

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import viewforge.model.ComponentDef
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * One entry in the cross-project component library (ADR-033, #209): the reusable [component] (the
 * *primary*) plus, for a nested component, its transitive dependency closure ([dependencies], #234) — the
 * other user components its tree references, bundled so the entry is self-contained on its own. Wrapped so
 * the on-disk file carries its own [libraryVersion] — independent of both the `.vforge`
 * [viewforge.model.Project.schemaVersion] and the prefs `prefsVersion`. The library is a *global*,
 * cross-project layer distinct from a document's own `Project.components` (ADR-024): a component a user
 * builds once and reuses in any project.
 *
 * [libraryVersion] is always emitted (the file must be self-describing) even though [VforgeJson] omits
 * defaults. [dependencies] is **additive with an empty default** (#234): a self-contained entry writes no
 * `dependencies` key — its file is byte-identical to a pre-#234 (`libraryVersion` 1) file — and any such
 * older file loads as a bundle with no dependencies, which is the correct reading of a self-contained entry.
 */
@Serializable
data class LibraryComponent(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val libraryVersion: Int = LIBRARY_VERSION,
    val component: ComponentDef,
    val dependencies: List<ComponentDef> = emptyList(),
) {
    companion object {
        const val LIBRARY_VERSION = 2
    }
}

/**
 * Reads and writes the cross-project component library (ADR-033) — a `library/` folder in the per-user
 * config dir with **one file per component** (`<id>.json`). Content is real user work, so it is written
 * through [VforgeJson] (the same `PropValue`-discriminated codec as `.vforge`, so props, modifiers, and
 * `RawExpression` round-trip identically) via [GuardedWriter], confined to the library [dir]. The store
 * lives in `core/project` beside [RecoveryStore] and takes the directory as a **parameter** (the caller
 * passes `ConfigDir.resolve().resolve("library")`), so `core/project` keeps no dependency on `core/prefs`
 * — the ADR-025 recovery-store shape, applied to a *set* of files.
 *
 * **Loading is per-file and skip-bad-file.** A missing folder is an empty library, and one unreadable or
 * corrupt file is skipped rather than aborting the rest — the durability the one-file-per-component layout
 * buys (a single bad entry can never take down the palette or lose the other components). The unit of
 * "fail" is one entry, not the whole set: unlike [ProjectStore] (a document fails loud, being *the* user
 * work) and unlike [viewforge.prefs.PreferencesStore] (chrome silently defaults), each library entry is an
 * independent piece of content.
 *
 * Identity is the [ComponentDef.id] carried *inside* each file, and the file name is derived from it
 * deterministically ([fileNameFor]); [save] and [remove] use the same derivation so they always agree.
 */
object ComponentLibraryStore {
    const val FILE_EXTENSION = ".json"

    /**
     * Every library entry in [dir] — the full [LibraryComponent] bundle (primary + any dependency closure,
     * #234) — name-sorted by its primary for a stable palette order. A missing directory yields an empty
     * list; an unreadable/corrupt individual file is skipped (never throws). Only regular `*.json` files are
     * considered — subdirectories and other files are ignored.
     */
    fun list(dir: Path): List<LibraryComponent> {
        if (!Files.isDirectory(dir)) return emptyList()
        val files = try {
            Files.list(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(FILE_EXTENSION) }
                    .sorted()
                    .toList()
            }
        } catch (_: IOException) {
            return emptyList()
        }
        return files.mapNotNull { readEntry(it) }.sortedBy { it.component.name.lowercase() }
    }

    /**
     * Atomically write [entry] to `[dir]/<primary-id>.json`, creating [dir] if needed. The file name derives
     * from the primary [ComponentDef.id]. Used for both adding a component and renaming one: a rename keeps
     * the same primary id, so it re-writes the same file with an updated name. Callers resolve name/id
     * collisions before saving.
     */
    fun save(entry: LibraryComponent, dir: Path) {
        val text = VforgeJson.encodeToString(LibraryComponent.serializer(), entry)
        GuardedWriter.write(dir.resolve(fileNameFor(entry.component.id)), text, root = dir)
    }

    /** Remove the library component [id]. Returns whether a file was actually deleted; absent ⇒ a no-op. */
    fun remove(id: String, dir: Path): Boolean {
        if (!Files.isDirectory(dir)) return false
        return GuardedWriter.delete(dir.resolve(fileNameFor(id)), root = dir)
    }

    /**
     * The file name a component [id] maps to, sanitized to safe filename characters. Ids are ULID-like
     * (`cmp_<ulid>`) so this is normally identity; `.` is deliberately *not* allowed through, so no id can
     * produce a `..` path segment or a trailing-dot name the [GuardedWriter] would reject.
     */
    fun fileNameFor(id: String): String {
        val safe = id.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .ifBlank { "component" }
        return "$safe$FILE_EXTENSION"
    }

    private fun readEntry(file: Path): LibraryComponent? = try {
        val text = Files.readString(file, StandardCharsets.UTF_8)
        VforgeJson.decodeFromString(LibraryComponent.serializer(), text)
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        // kotlinx.serialization can surface a malformed document as IllegalArgumentException.
        null
    }
}

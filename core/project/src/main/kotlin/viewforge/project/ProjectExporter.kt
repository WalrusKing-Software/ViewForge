package viewforge.project

import java.nio.file.Files
import java.nio.file.Path

/**
 * One file to write during an export. [path] is always **project-relative** and forward-slash
 * separated (e.g. `src/main/kotlin/HomeScreen.kt`); it is resolved segment-by-segment under the
 * chosen export root so the same bundle writes identically on Windows and POSIX.
 */
sealed interface ExportFile {
    val path: String
}

/** A generated text file (Kotlin source, `build.gradle.kts`, `.gitignore`, …), written as UTF-8. */
data class TextFile(override val path: String, val content: String) : ExportFile

/**
 * A binary artifact to copy verbatim — the Gradle wrapper jar and the `gradlew` scripts in an
 * exported project (M7). [executable] requests the POSIX execute bit so a generated `gradlew` runs
 * without a manual `chmod` (a no-op on Windows). Custom [equals]/[hashCode] because [bytes] is an
 * array (reference identity by default), which would break value comparison in tests.
 */
class BinaryFile(override val path: String, val bytes: ByteArray, val executable: Boolean = false) : ExportFile {
    override fun equals(other: Any?): Boolean = this === other ||
        (
            other is BinaryFile &&
                path == other.path &&
                executable == other.executable &&
                bytes.contentEquals(other.bytes)
            )

    override fun hashCode(): Int = (path.hashCode() * 31 + executable.hashCode()) * 31 + bytes.contentHashCode()
}

/**
 * Writes an export bundle to a user-chosen directory (G4/G5). Framework-agnostic: it takes a flat
 * list of [ExportFile]s (produced by a framework package's target exporter) and writes each through
 * the [GuardedWriter], so every path-safety guarantee — root confinement (FW-2/FW-8), symlink and
 * traversal rejection (FW-4), reserved-name checks (FW-3), atomic replace (FW-6) — holds for export
 * exactly as it does for saving the project (CLAUDE.md rule 6, SECURITY §5).
 *
 * Overwrite is a *confirmed* action (FW-5): callers show [conflicts] to the user before calling
 * [write]. This type does not prompt — that is UI — it only reports and writes.
 */
object ProjectExporter {
    /**
     * The subset of [files] that already exist under [root], as their relative paths, so the UI can
     * list exactly what an export would replace before the user confirms (FW-5). Never writes.
     */
    fun conflicts(root: Path, files: List<ExportFile>): List<String> {
        val realRoot = root.toRealPath()
        return files.filter { Files.exists(resolveInRoot(realRoot, it.path)) }.map { it.path }
    }

    /**
     * Writes every file in [files] under [root]. All destinations are resolved and checked to be
     * inside the root **before any write** (defense in depth over the per-file guard), so a traversal
     * path can't slip a single file outside the root after others have been written. [backup] copies a
     * prior file to `<name>.bak` before replacing it.
     */
    fun write(root: Path, files: List<ExportFile>, backup: Boolean = false): List<String> {
        val realRoot = root.toRealPath()
        // Pre-validate all targets up front: a bundle with any escaping path is rejected wholesale
        // rather than written partially.
        val targets = files.map { it to resolveInRoot(realRoot, it.path) }
        targets.forEach { (file, target) ->
            if (!target.normalize().startsWith(realRoot)) {
                throw UnsafeWriteException("Export path '${file.path}' escapes the export root '$realRoot'")
            }
        }
        targets.forEach { (file, target) ->
            when (file) {
                is TextFile -> GuardedWriter.write(target, file.content, root = realRoot, backup = backup)
                is BinaryFile ->
                    GuardedWriter.writeBytes(
                        target,
                        file.bytes,
                        root = realRoot,
                        backup = backup,
                        executable = file.executable,
                    )
            }
        }
        return files.map { it.path }
    }

    /** Resolves a forward-slash relative [path] under [root] one segment at a time (cross-platform). */
    private fun resolveInRoot(root: Path, path: String): Path =
        path.split('/').filter { it.isNotEmpty() }.fold(root) { acc, segment -> acc.resolve(segment) }
}

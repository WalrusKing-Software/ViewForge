package viewforge.project

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Raised when a write is refused because the destination path is unsafe (SECURITY §5). */
class UnsafeWriteException(message: String) : RuntimeException(message)

/**
 * The single guarded entry point for writing project/output files (CLAUDE.md rule 6, SECURITY §5
 * FW-1). No other code calls `Files.writeString`/`writeText` directly — keeping every path-safety
 * check in one place.
 *
 * Guarantees:
 * - **Atomic** (FW-6): content is written to a temp file in the destination directory and then
 *   moved into place, so an interrupted write can never leave a half-written or truncated file.
 * - **Confined to a root** (FW-2/FW-4/FW-8): when [root] is given, the resolved real path must sit
 *   inside it — checked after resolution, following symlinks — so nothing escapes via `..` or a
 *   symlinked directory.
 * - **Sane filenames** (FW-3): reserved Windows device names and trailing dots/spaces are rejected.
 * - **Optional backup** (DATA_MODEL §10 rule 6): the prior file can be copied to `<name>.bak`
 *   before replacement.
 */
object GuardedWriter {
    private val RESERVED_NAMES: Set<String> =
        buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL"))
            (1..9).forEach {
                add("COM$it")
                add("LPT$it")
            }
        }

    private const val MAX_FILENAME_LENGTH = 255

    fun write(target: Path, content: String, root: Path? = null, backup: Boolean = false) {
        val normalized = target.toAbsolutePath().normalize()
        val fileName = normalized.fileName?.toString()
            ?: throw UnsafeWriteException("Destination has no file name: $target")
        validateFileName(fileName)

        val parent = normalized.parent
            ?: throw UnsafeWriteException("Destination has no parent directory: $target")

        // Validate the destination is inside the permitted root BEFORE touching the filesystem.
        if (root != null) assertInsideRoot(normalized, parent, root)
        Files.createDirectories(parent)

        if (backup && Files.exists(normalized)) {
            val backupPath = parent.resolve("$fileName.bak")
            Files.copy(normalized, backupPath, StandardCopyOption.REPLACE_EXISTING)
        }

        val temp = Files.createTempFile(parent, "$fileName.", ".tmp")
        try {
            Files.write(temp, content.toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(temp, normalized, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                // Some filesystems can't move atomically across their internal boundaries; fall back
                // to a plain replace. Still far safer than writing the destination in place.
                Files.move(temp, normalized, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun validateFileName(fileName: String) {
        if (fileName.isBlank()) throw UnsafeWriteException("Empty file name")
        if (fileName.length > MAX_FILENAME_LENGTH) {
            throw UnsafeWriteException("File name exceeds $MAX_FILENAME_LENGTH characters")
        }
        if (fileName != fileName.trimEnd('.', ' ')) {
            throw UnsafeWriteException("File name must not end with a dot or space: '$fileName'")
        }
        val stem = fileName.substringBefore('.').uppercase()
        if (stem in RESERVED_NAMES) {
            throw UnsafeWriteException("Reserved device name is not a valid file name: '$fileName'")
        }
    }

    private fun assertInsideRoot(normalized: Path, parent: Path, root: Path) {
        val realRoot = root.toAbsolutePath().normalize().let { if (Files.exists(it)) it.toRealPath() else it }
        // The target itself may not exist yet, so resolve the (existing) parent's real path and
        // re-attach the file name — this catches a symlinked directory escaping the root.
        val realParent = if (Files.exists(parent)) parent.toRealPath() else parent
        val resolved = realParent.resolve(normalized.fileName)
        if (!resolved.startsWith(realRoot)) {
            throw UnsafeWriteException("Destination '$resolved' is outside the permitted root '$realRoot'")
        }
    }
}

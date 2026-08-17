package viewforge.project

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Formats and writes a local crash log (S6, #106). **Strictly local**: nothing here transmits anything
 * over a network (SECURITY §GC / ADR-011 offline-only) — it only writes a file under the caller-supplied
 * config directory through the guarded writer. The report is diagnostics; the working *document* is
 * preserved separately by the autosave/recovery sidecar (D4, [RecoveryStore]).
 *
 * Writing is **total** — [write] never throws — so it can run inside an uncaught-exception handler
 * without masking the original crash or blocking exit (best-effort: a failure to log is swallowed).
 * Confined to [dir] via [GuardedWriter], so a crash log can never escape the config directory.
 */
object CrashReporter {
    const val CRASH_DIR = "crash"

    // File-name-safe (no ':'), sortable, UTC so logs from any timezone order consistently.
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

    /** The crash-log subdirectory under a config [dir]. */
    fun logDir(dir: Path): Path = dir.resolve(CRASH_DIR)

    /**
     * A human-readable crash report for [throwable] at [at] (epoch millis), with an optional free-form
     * [context] note (e.g. the crashing thread and active screen). Pure: renders the full stack trace,
     * including causes, to text.
     */
    fun format(throwable: Throwable, at: Long, context: String? = null): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("ViewForge crash report")
            pw.println("time: ${Instant.ofEpochMilli(at)}")
            if (!context.isNullOrBlank()) pw.println("context: $context")
            pw.println()
            throwable.printStackTrace(pw)
        }
        return sw.toString()
    }

    /**
     * Write a crash report for [throwable] to `[dir]/crash/crash-<timestamp>.log`, returning the path
     * written or null on any failure. Never throws.
     */
    fun write(dir: Path, throwable: Throwable, at: Long = System.currentTimeMillis(), context: String? = null): Path? =
        runCatching {
            val crashDir = logDir(dir)
            val file = crashDir.resolve("crash-${STAMP.format(Instant.ofEpochMilli(at))}.log")
            // Confine to the crash dir itself: the log always sits directly in it, and rooting here avoids
            // GuardedWriter comparing a not-yet-created nested parent against the real path of an outer root.
            GuardedWriter.write(file, format(throwable, at, context), root = crashDir)
            file
        }.getOrNull()
}

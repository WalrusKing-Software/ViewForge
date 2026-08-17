package viewforge.project

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The local crash reporter (S6, #106). Diagnostics must never make a bad situation worse: writing is
 * total (a failure to log is swallowed, never thrown), confined to the config dir, and captures enough
 * to diagnose (message, stack trace, context). Runs against a temp dir — no real config directory.
 */
class CrashReporterTest {
    private fun tempDir(): Path = Files.createTempDirectory("viewforge-crash-test")

    @Test
    fun `format captures the message, stack trace and context`() {
        val text = CrashReporter.format(IllegalStateException("boom"), at = 1_723_000_000_000L, context = "thread=main")
        assertContains(text, "ViewForge crash report")
        assertContains(text, "IllegalStateException")
        assertContains(text, "boom")
        assertContains(text, "context: thread=main")
        assertContains(text, "at viewforge.project.CrashReporterTest") // a real stack frame
    }

    @Test
    fun `format renders the cause chain`() {
        val e = RuntimeException("outer", IllegalArgumentException("inner cause"))
        val text = CrashReporter.format(e, at = 1L)
        assertContains(text, "outer")
        assertContains(text, "Caused by")
        assertContains(text, "inner cause")
    }

    @Test
    fun `write puts a log under the crash dir and returns its path`() {
        val dir = tempDir()
        val path = CrashReporter.write(dir, IllegalStateException("kaboom"), at = 1_723_000_000_000L)

        assertNotNull(path)
        assertTrue(Files.exists(path))
        assertEquals(CrashReporter.logDir(dir), path.parent)
        assertContains(path.fileName.toString(), "crash-")
        assertContains(path.readText(), "kaboom")
    }

    @Test
    fun `write is total - a dir that is actually a file yields null, not a throw`() {
        val notADir = Files.createTempFile("viewforge-crash-notadir", ".tmp")
        assertNull(CrashReporter.write(notADir, IllegalStateException("x")))
    }
}

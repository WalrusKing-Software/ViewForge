package viewforge.project

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The crash-recovery sidecar store (D4). Recovery must *never lose work* and must *never block launch*:
 * a round-trip preserves the exact document, and a missing/corrupt file loads as `null` rather than
 * throwing. Runs entirely against a temp dir — no real config directory touched.
 */
class RecoveryStoreTest {
    private fun tempDir(): Path = Files.createTempDirectory("viewforge-recovery-test")

    @Test
    fun `save then load round-trips the document, path and timestamp`() {
        val dir = tempDir()
        val snap = RecoverySnapshot(
            originalPath = "C:/work/App.vforge",
            savedAt = 1_723_000_000_000L,
            document = Fixtures.demoProject(),
        )
        RecoveryStore.save(snap, dir)

        val loaded = RecoveryStore.load(dir)
        assertEquals(snap, loaded)
        assertEquals(Fixtures.demoProject(), loaded?.document)
    }

    @Test
    fun `a never-saved document round-trips with a null originalPath`() {
        val dir = tempDir()
        val snap = RecoverySnapshot(originalPath = null, savedAt = 1L, document = Fixtures.minimalProject())
        RecoveryStore.save(snap, dir)

        assertNull(RecoveryStore.load(dir)?.originalPath)
        assertEquals(Fixtures.minimalProject(), RecoveryStore.load(dir)?.document)
    }

    @Test
    fun `load returns null when no sidecar exists`() {
        assertNull(RecoveryStore.load(tempDir()))
    }

    @Test
    fun `load returns null on a corrupt sidecar rather than throwing`() {
        val dir = tempDir()
        dir.createDirectories()
        dir.resolve(RecoveryStore.FILE_NAME).writeText("{ not valid json ")
        assertNull(RecoveryStore.load(dir))
    }

    @Test
    fun `clear removes the sidecar and is a no-op when absent`() {
        val dir = tempDir()
        RecoveryStore.save(RecoverySnapshot(savedAt = 1L, document = Fixtures.minimalProject()), dir)
        assertTrue(Files.exists(dir.resolve(RecoveryStore.FILE_NAME)))

        RecoveryStore.clear(dir)
        assertFalse(Files.exists(dir.resolve(RecoveryStore.FILE_NAME)))
        RecoveryStore.clear(dir) // absent — must not throw
    }
}

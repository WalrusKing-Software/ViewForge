package viewforge.project

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cross-project component library store (ADR-033, #209). The library is real user content held as one
 * file per component, so a round-trip preserves each definition exactly, one corrupt file never sinks the
 * rest, and remove/re-save behave file-by-file. Runs entirely against a temp dir — no real config dir.
 */
class ComponentLibraryStoreTest {
    private fun tempDir(): Path = Files.createTempDirectory("viewforge-library-test")

    @Test
    fun `save then list round-trips a component`() {
        val dir = tempDir()
        val comp = Fixtures.component("cmp_01", references = null)
        ComponentLibraryStore.save(comp, dir)

        assertEquals(listOf(comp), ComponentLibraryStore.list(dir))
    }

    @Test
    fun `list is empty when the directory is absent`() {
        assertEquals(emptyList(), ComponentLibraryStore.list(tempDir().resolve("nope")))
    }

    @Test
    fun `list returns every saved component, name-sorted`() {
        val dir = tempDir()
        ComponentLibraryStore.save(Fixtures.component("cmp_b").copy(name = "Beta"), dir)
        ComponentLibraryStore.save(Fixtures.component("cmp_a").copy(name = "alpha"), dir)
        ComponentLibraryStore.save(Fixtures.component("cmp_c").copy(name = "Gamma"), dir)

        assertEquals(listOf("alpha", "Beta", "Gamma"), ComponentLibraryStore.list(dir).map { it.name })
    }

    @Test
    fun `re-saving under the same id overwrites in place, the rename path`() {
        val dir = tempDir()
        val comp = Fixtures.component("cmp_01").copy(name = "Original")
        ComponentLibraryStore.save(comp, dir)
        ComponentLibraryStore.save(comp.copy(name = "Renamed"), dir)

        val loaded = ComponentLibraryStore.list(dir)
        assertEquals(1, loaded.size)
        assertEquals("Renamed", loaded.single().name)
    }

    @Test
    fun `a corrupt file is skipped, leaving the rest of the library intact`() {
        val dir = tempDir()
        dir.createDirectories()
        ComponentLibraryStore.save(Fixtures.component("cmp_ok").copy(name = "Good"), dir)
        dir.resolve("cmp_bad${ComponentLibraryStore.FILE_EXTENSION}").writeText("{ not valid json ")

        val loaded = ComponentLibraryStore.list(dir)
        assertEquals(listOf("Good"), loaded.map { it.name })
    }

    @Test
    fun `remove deletes the entry and is a no-op when absent`() {
        val dir = tempDir()
        ComponentLibraryStore.save(Fixtures.component("cmp_01"), dir)
        assertTrue(Files.exists(dir.resolve(ComponentLibraryStore.fileNameFor("cmp_01"))))

        assertTrue(ComponentLibraryStore.remove("cmp_01", dir))
        assertFalse(Files.exists(dir.resolve(ComponentLibraryStore.fileNameFor("cmp_01"))))
        assertFalse(ComponentLibraryStore.remove("cmp_01", dir)) // already gone
        assertEquals(emptyList(), ComponentLibraryStore.list(dir))
    }

    @Test
    fun `fileNameFor sanitizes unsafe characters so a hostile id stays a plain file`() {
        val name = ComponentLibraryStore.fileNameFor("../evil/id")
        assertFalse(name.contains('/'))
        assertFalse(name.contains(".."))
        assertTrue(name.endsWith(ComponentLibraryStore.FILE_EXTENSION))
    }
}

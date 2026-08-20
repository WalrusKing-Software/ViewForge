package viewforge.project

import viewforge.model.ComponentDef
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cross-project component library store (ADR-033, #209; closure bundling #234). The library is real
 * user content held as one file per entry, so a round-trip preserves each bundle exactly, one corrupt file
 * never sinks the rest, remove/re-save behave file-by-file, and a nested entry carries its dependency
 * closure. Runs entirely against a temp dir — no real config dir.
 */
class ComponentLibraryStoreTest {
    private fun tempDir(): Path = Files.createTempDirectory("viewforge-library-test")

    private fun entry(comp: ComponentDef, dependencies: List<ComponentDef> = emptyList()) =
        LibraryComponent(component = comp, dependencies = dependencies)

    @Test
    fun `save then list round-trips an entry`() {
        val dir = tempDir()
        val comp = Fixtures.component("cmp_01", references = null)
        ComponentLibraryStore.save(entry(comp), dir)

        assertEquals(listOf(entry(comp)), ComponentLibraryStore.list(dir))
    }

    @Test
    fun `list is empty when the directory is absent`() {
        assertEquals(emptyList(), ComponentLibraryStore.list(tempDir().resolve("nope")))
    }

    @Test
    fun `a nested entry round-trips its whole dependency closure`() {
        val dir = tempDir()
        val leaf = Fixtures.component("cmp_leaf").copy(name = "Leaf")
        val primary = Fixtures.component("cmp_root", references = "cmp_leaf").copy(name = "Card")
        ComponentLibraryStore.save(entry(primary, dependencies = listOf(leaf)), dir)

        val loaded = ComponentLibraryStore.list(dir).single()
        assertEquals(primary, loaded.component)
        assertEquals(listOf(leaf), loaded.dependencies)
    }

    @Test
    fun `list returns every saved entry, name-sorted by its primary`() {
        val dir = tempDir()
        ComponentLibraryStore.save(entry(Fixtures.component("cmp_b").copy(name = "Beta")), dir)
        ComponentLibraryStore.save(entry(Fixtures.component("cmp_a").copy(name = "alpha")), dir)
        ComponentLibraryStore.save(entry(Fixtures.component("cmp_c").copy(name = "Gamma")), dir)

        assertEquals(listOf("alpha", "Beta", "Gamma"), ComponentLibraryStore.list(dir).map { it.component.name })
    }

    @Test
    fun `re-saving under the same primary id overwrites in place, the rename path`() {
        val dir = tempDir()
        val comp = Fixtures.component("cmp_01").copy(name = "Original")
        ComponentLibraryStore.save(entry(comp), dir)
        ComponentLibraryStore.save(entry(comp.copy(name = "Renamed")), dir)

        val loaded = ComponentLibraryStore.list(dir)
        assertEquals(1, loaded.size)
        assertEquals("Renamed", loaded.single().component.name)
    }

    @Test
    fun `a self-contained entry omits the dependencies field on disk`() {
        val dir = tempDir()
        ComponentLibraryStore.save(entry(Fixtures.component("cmp_01")), dir)

        val text = dir.resolve(ComponentLibraryStore.fileNameFor("cmp_01")).readText()
        assertFalse(text.contains("dependencies")) // empty default is omitted (VforgeJson), so the file stays lean
    }

    @Test
    fun `an older libraryVersion 1 file with no dependencies loads as a self-contained entry`() {
        val dir = tempDir()
        dir.createDirectories()
        val comp = Fixtures.component("cmp_v1").copy(name = "Legacy")
        // A pre-#234 file: libraryVersion 1, a bare component, no dependencies key at all.
        val componentJson = VforgeJson.encodeToString(ComponentDef.serializer(), comp)
        dir.resolve(ComponentLibraryStore.fileNameFor("cmp_v1"))
            .writeText("""{"libraryVersion":1,"component":$componentJson}""")

        val loaded = ComponentLibraryStore.list(dir).single()
        assertEquals(comp, loaded.component)
        assertEquals(emptyList(), loaded.dependencies)
    }

    @Test
    fun `a corrupt file is skipped, leaving the rest of the library intact`() {
        val dir = tempDir()
        dir.createDirectories()
        ComponentLibraryStore.save(entry(Fixtures.component("cmp_ok").copy(name = "Good")), dir)
        dir.resolve("cmp_bad${ComponentLibraryStore.FILE_EXTENSION}").writeText("{ not valid json ")

        val loaded = ComponentLibraryStore.list(dir)
        assertEquals(listOf("Good"), loaded.map { it.component.name })
    }

    @Test
    fun `remove deletes the entry and is a no-op when absent`() {
        val dir = tempDir()
        ComponentLibraryStore.save(entry(Fixtures.component("cmp_01")), dir)
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

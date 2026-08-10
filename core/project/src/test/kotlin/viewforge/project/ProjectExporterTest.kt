package viewforge.project

import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The export writer (M7, G4/G5). It layers only overwrite reporting and multi-file orchestration over
 * the [GuardedWriter]; these tests focus on that layer — nested paths, binary bytes, the executable
 * bit, conflict detection, and that a traversal path in the bundle is rejected wholesale.
 */
class ProjectExporterTest {
    @Test
    fun `writes text and binary files under nested directories`() {
        val root = Files.createTempDirectory("export-write")
        val bytes = byteArrayOf(0, 1, 2, 3, 127, -1)
        ProjectExporter.write(
            root,
            listOf(
                TextFile("src/main/kotlin/HomeScreen.kt", "fun HomeScreen() {}\n"),
                BinaryFile("gradle/wrapper/gradle-wrapper.jar", bytes),
            ),
        )
        assertEquals("fun HomeScreen() {}\n", root.resolve("src/main/kotlin/HomeScreen.kt").readText())
        assertTrue(bytes.contentEquals(root.resolve("gradle/wrapper/gradle-wrapper.jar").readBytes()))
    }

    @Test
    fun `conflicts lists only files that already exist`() {
        val root = Files.createTempDirectory("export-conflicts")
        val files = listOf(TextFile("a.txt", "a"), TextFile("sub/b.txt", "b"))
        assertEquals(emptyList(), ProjectExporter.conflicts(root, files))

        ProjectExporter.write(root, listOf(files[0]))
        assertEquals(listOf("a.txt"), ProjectExporter.conflicts(root, files))
    }

    @Test
    fun `a path escaping the export root is rejected before anything is written`() {
        val root = Files.createTempDirectory("export-escape")
        assertFailsWith<UnsafeWriteException> {
            ProjectExporter.write(root, listOf(TextFile("ok.txt", "x"), TextFile("../escape.txt", "bad")))
        }
        // The good file must not have been written either — the bundle is rejected wholesale.
        assertTrue(Files.list(root).use { it.count() } == 0L, "no files should be written when a path is unsafe")
    }

    @Test
    fun `the executable flag sets the exec bit where the filesystem supports it`() {
        val root = Files.createTempDirectory("export-exec")
        ProjectExporter.write(root, listOf(BinaryFile("gradlew", byteArrayOf(1, 2, 3), executable = true)))
        val gradlew = root.resolve("gradlew")
        assertTrue(Files.exists(gradlew))

        val posix = Files.getFileAttributeView(gradlew, PosixFileAttributeView::class.java)
        if (posix != null) {
            val perms = posix.readAttributes().permissions().map { it.name }
            assertTrue(perms.any { it.endsWith("EXECUTE") }, "expected an execute permission, got $perms")
        }
        // On non-POSIX filesystems (Windows) there is no exec bit; the successful write above is enough.
    }
}

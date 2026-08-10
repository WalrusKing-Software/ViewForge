package viewforge.project

import viewforge.model.Asset
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Guarded writer (SECURITY §5) and load-time validation (PF-2/PF-3/PF-5). */
class PersistenceSafetyTest {
    // --- GuardedWriter ---

    @Test
    fun `writes content and reads it back`() {
        val dir = Files.createTempDirectory("gw-write")
        val target = dir.resolve("out.vforge")
        GuardedWriter.write(target, "hello", root = dir)
        assertEquals("hello", target.readText())
    }

    @Test
    fun `write leaves no temp file behind`() {
        val dir = Files.createTempDirectory("gw-temp")
        GuardedWriter.write(dir.resolve("out.vforge"), "x", root = dir)
        val leftovers = dir.listDirectoryEntries().map { it.fileName.toString() }.filter { it.endsWith(".tmp") }
        assertTrue(leftovers.isEmpty(), "leftover temp files: $leftovers")
    }

    @Test
    fun `backup copies the previous content before overwriting`() {
        val dir = Files.createTempDirectory("gw-backup")
        val target = dir.resolve("out.vforge")
        GuardedWriter.write(target, "v1", root = dir)
        GuardedWriter.write(target, "v2", root = dir, backup = true)
        assertEquals("v2", target.readText())
        assertEquals("v1", dir.resolve("out.vforge.bak").readText())
    }

    @Test
    fun `reserved Windows device names are rejected`() {
        val dir = Files.createTempDirectory("gw-reserved")
        assertFailsWith<UnsafeWriteException> { GuardedWriter.write(dir.resolve("CON.vforge"), "x", root = dir) }
    }

    @Test
    fun `file names ending in a dot or space are rejected`() {
        val dir = Files.createTempDirectory("gw-trailing")
        assertFailsWith<UnsafeWriteException> { GuardedWriter.write(dir.resolve("bad ."), "x", root = dir) }
    }

    @Test
    fun `writing outside the permitted root is rejected`() {
        val root = Files.createTempDirectory("gw-root")
        val outside = Files.createTempDirectory("gw-outside")
        assertFailsWith<UnsafeWriteException> {
            GuardedWriter.write(outside.resolve("escape.txt"), "x", root = root)
        }
    }

    // --- ProjectValidator ---

    @Test
    fun `the demo project passes validation`() {
        ProjectValidator.validate(Fixtures.demoProject())
    }

    @Test
    fun `excessive tree depth is rejected`() {
        val project = Fixtures.minimalProject().copy(
            screens = listOf(viewforge.model.Screen(id = "s", name = "S", root = Fixtures.linearTree(5))),
        )
        assertFailsWith<ProjectValidationException> {
            ProjectValidator.validate(project, VforgeLimits(maxDepth = 3))
        }
    }

    @Test
    fun `excessive node count is rejected`() {
        val project = Fixtures.minimalProject().copy(
            screens = listOf(viewforge.model.Screen(id = "s", name = "S", root = Fixtures.wideTree(50))),
        )
        assertFailsWith<ProjectValidationException> {
            ProjectValidator.validate(project, VforgeLimits(maxNodes = 10))
        }
    }

    @Test
    fun `asset paths escaping the project root are rejected`() {
        listOf("../secret.png", "/etc/passwd", "C:\\Windows\\x.png", "\\\\server\\share").forEach { badPath ->
            val project = Fixtures.minimalProject().copy(
                assets = listOf(Asset(id = "a", type = "image", path = badPath)),
            )
            assertFailsWith<ProjectValidationException>("expected rejection of '$badPath'") {
                ProjectValidator.validate(project)
            }
        }
    }

    @Test
    fun `a cycle in user-component references is detected`() {
        val project = Fixtures.minimalProject().copy(
            components = listOf(Fixtures.component("a", references = "b"), Fixtures.component("b", references = "a")),
        )
        assertFailsWith<ProjectValidationException> { ProjectValidator.validate(project) }
    }

    @Test
    fun `load rejects a saved project whose asset path escapes the root`() {
        val project = Fixtures.minimalProject().copy(
            assets = listOf(Asset(id = "a", type = "image", path = "../../etc/passwd")),
        )
        val tmp = Files.createTempDirectory("vforge-badasset").resolve("bad.vforge")
        ProjectStore.save(project, tmp)
        val result = ProjectStore.load(tmp)
        assertTrue(result is LoadResult.Failure && result.kind == LoadFailure.VALIDATION_FAILED, "got $result")
    }
}

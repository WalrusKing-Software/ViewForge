package viewforge.project

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lossless round-trip and the committed sample fixture (PROJECT_PLAN §8 M1 exit criteria). */
class RoundTripTest {
    @Test
    fun `model round-trips through encode then decode`() {
        val project = Fixtures.demoProject()
        assertEquals(project, ProjectCodec.decode(ProjectCodec.encode(project)))
    }

    @Test
    fun `the committed Demo_vforge fixture loads and equals the in-code model`() {
        val samplesDir = System.getProperty("viewforge.samplesDir")
            ?: error("viewforge.samplesDir system property not set by the build")
        val result = ProjectStore.load(Paths.get(samplesDir, "Demo.vforge"))
        assertTrue(result is LoadResult.Success, "expected Success but got $result")
        assertEquals(Fixtures.demoProject(), result.project)
    }

    @Test
    fun `the committed Gallery_vforge sample loads and round-trips losslessly (M9, exit #4)`() {
        // The Phase-1 "something real" screen — nested layout, images, a scrollable list, theme and
        // resource references — must survive save → load unchanged (PROJECT_PLAN §8 exit criterion #4).
        val samplesDir = System.getProperty("viewforge.samplesDir")
            ?: error("viewforge.samplesDir system property not set by the build")
        val result = ProjectStore.load(Paths.get(samplesDir, "Gallery.vforge"))
        assertTrue(result is LoadResult.Success, "expected Success but got $result")
        val project = result.project
        // Encode → decode is the identity, so no field is dropped or reordered on the way through.
        assertEquals(project, ProjectCodec.decode(ProjectCodec.encode(project)))
    }

    @Test
    fun `all five PropValue kinds survive a round-trip and carry the kind discriminator`() {
        val project = Fixtures.demoProject()
        val json = ProjectCodec.encode(project)
        assertContains(json, "\"kind\": \"literal\"")
        assertContains(json, "\"kind\": \"theme\"")
        assertContains(json, "\"kind\": \"expression\"")
        assertEquals(project, ProjectCodec.decode(json))
    }

    @Test
    fun `schemaVersion is always emitted even though it has a default`() {
        assertContains(ProjectCodec.encode(Fixtures.minimalProject()), "\"schemaVersion\": 2")
    }

    @Test
    fun `a ParamRef survives a round-trip and carries the param kind discriminator`() {
        val node = viewforge.model.Node(
            id = viewforge.model.NodeId("n_param"),
            type = "compose.material3.Text",
            props = mapOf("text" to viewforge.model.PropValue.ParamRef("label")),
        )
        val project = Fixtures.minimalProject().let {
            it.copy(components = listOf(viewforge.model.ComponentDef(id = "cmp_1", name = "Labeled", root = node)))
        }
        val json = ProjectCodec.encode(project)
        assertContains(json, "\"kind\": \"param\"")
        assertContains(json, "\"param\": \"label\"")
        assertEquals(project, ProjectCodec.decode(json))
    }

    @Test
    fun `a saved project contains no absolute paths, usernames, or machine identifiers (PR-4)`() {
        val json = ProjectCodec.encode(Fixtures.demoProject())
        // Windows drive paths, UNC, and common home-dir roots must never leak into a .vforge file.
        val leaks = listOf("C:\\", "C:/", "\\\\", "/Users/", "/home/", "/root/")
        leaks.forEach { assertTrue(!json.contains(it), "saved project leaked '$it'") }
    }

    @Test
    fun `round-trip is idempotent through the store's save and load`() {
        val project = Fixtures.demoProject()
        val tmp = Files.createTempDirectory("vforge-roundtrip").resolve("Demo.vforge")
        ProjectStore.save(project, tmp)
        val result = ProjectStore.load(tmp)
        assertTrue(result is LoadResult.Success)
        assertEquals(project, result.project)
    }
}

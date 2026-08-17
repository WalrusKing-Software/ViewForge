package viewforge.app

import viewforge.model.Node
import viewforge.project.ProjectCodec
import viewforge.project.ProjectValidator
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sample the editor opens on launch is the Phase-1 "something real" (M9): it must be a
 * structurally valid project (the same invariants a loaded `.vforge` is held to, SECURITY §3), it
 * must exercise every component exit criterion #1 names, and its in-code form must stay byte-identical
 * to the committed `samples/Gallery.vforge` so the two never drift.
 */
class SampleProjectTest {
    @Test
    fun `sample project passes structural validation`() {
        ProjectValidator.validate(sampleProject()) // throws on any violation
    }

    @Test
    fun `sample exercises every Phase-1 exit-criterion component`() {
        val root = sampleProject().screens.single().root
        val types = root.allTypes()
        // Exit criterion #1: nested Column/Row/Box, text, buttons, images, and a scrollable list.
        listOf(
            "compose.foundation.layout.Column",
            "compose.foundation.layout.Row",
            "compose.foundation.layout.Box",
            "compose.material3.Text",
            "compose.material3.Button",
            "compose.foundation.Image",
            "compose.foundation.lazy.LazyColumn",
        ).forEach { assertTrue(it in types, "sample is missing $it") }
    }

    @Test
    fun `every Image source resolves to a listed asset`() {
        val project = sampleProject()
        val assetIds = project.assets.map { it.id }.toSet()
        project.screens.single().root.allNodes()
            .filter { it.type == "compose.foundation.Image" }
            .forEach { image ->
                val ref = image.props["source"] as? viewforge.model.PropValue.ResourceRef
                assertTrue(ref != null && ref.assetId in assetIds, "Image ${image.id.value} has an unresolved source")
            }
    }

    @Test
    fun `in-code sample stays in lockstep with the committed Gallery_vforge`() {
        val samplesDir = System.getProperty("viewforge.samplesDir")
            ?: error("viewforge.samplesDir system property not set by the build")
        val onDisk = Files.readString(Paths.get(samplesDir, "Gallery.vforge"))
        // Structural equality (decode both) proves they model the same project; the generator writes
        // the file from this same encoder, so they are byte-identical by construction.
        assertEquals(sampleProject(), ProjectCodec.decode(onDisk))
        assertEquals(ProjectCodec.encode(sampleProject()), onDisk.replace("\r\n", "\n"))
    }
}

private fun Node.allNodes(): List<Node> = listOf(this) + (children + slots.values.flatten()).flatMap { it.allNodes() }

private fun Node.allTypes(): Set<String> = allNodes().map { it.type }.toSet()

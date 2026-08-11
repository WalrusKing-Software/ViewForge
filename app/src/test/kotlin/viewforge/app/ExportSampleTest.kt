package viewforge.app

import viewforge.packages.compose.targets.DesktopExporter
import viewforge.project.BinaryFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end guard for the exported sample (M9 / ADR-021): a Gradle export of the Gallery must ship its
 * image assets under `src/main/resources/` with their real bytes, so the exported project *runs* — not
 * merely compiles. Without this the `Image` rows render blank at run time (`painterResource` throws
 * `Resource … not found`, dropping the whole `LazyColumn` subtree).
 */
class ExportSampleTest {
    @Test
    fun `gradle export of the sample bundles its image assets with real bytes`() {
        val project = sampleProject()
        val files = DesktopExporter.gradleProject(project) { asset -> classpathAssetBytes(asset.path) }

        for (asset in project.assets) {
            val exported = files.singleOrNull { it.path == "src/main/resources/${asset.path}" } as? BinaryFile
            assertTrue(exported != null, "export is missing ${asset.path}")
            assertTrue(exported.bytes.isNotEmpty(), "${asset.path} was exported empty")
            // The exported bytes are exactly the source asset's bytes.
            assertTrue(
                exported.bytes.contentEquals(classpathAssetBytes(asset.path)),
                "${asset.path} bytes diverged from the source asset",
            )
        }
    }
}

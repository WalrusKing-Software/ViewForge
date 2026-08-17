package viewforge.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Asset byte resolution (#141, ADR-021): the canvas loader and Gradle export share [assetBytes], which
 * reads the open project's on-disk `assets/` first and falls back to the classpath (the bundled sample).
 * A path that escapes the project dir must resolve to nothing on disk (defense in depth over the PF-5
 * load-time check) rather than reading outside the project.
 */
class AssetBytesTest {
    private val tmp: Path = Files.createTempDirectory("vforge-asset-bytes-test")

    @AfterTest
    fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `reads an imported asset from the project directory`() {
        val bytes = byteArrayOf(9, 8, 7)
        tmp.resolve("assets").createDirectories()
        Files.write(tmp.resolve("assets/hero.png"), bytes)

        assertTrue(bytes.contentEquals(assetBytes(tmp, "assets/hero.png")))
    }

    @Test
    fun `a path escaping the project dir is refused (no classpath match)`() {
        // "../secret" would resolve outside tmp; the confinement check rejects it and, absent a classpath
        // resource of that name, the result is null — never bytes from outside the project.
        assertNull(assetBytes(tmp, "../secret"))
    }

    @Test
    fun `a missing project file with no project dir falls back to the classpath`() {
        // No project dir (never-saved document): resolution goes straight to the classpath, which has no
        // such resource here, so null. The bundled sample's own assets are covered by the fidelity tests.
        assertNull(assetBytes(null, "assets/does-not-exist.png"))
    }
}

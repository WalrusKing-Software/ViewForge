package viewforge.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import viewforge.model.Asset
import java.nio.file.Files
import java.nio.file.Path

/**
 * The editor-side half of the `Image` seam (ARCHITECTURE §3): resolves a node's `ResourceRef` asset id
 * to a decoded [ImageBitmap] for the canvas, keeping all disk/decoding concerns in `:app` so the render
 * layer stays pure (see `RenderContext.imageLoader`).
 *
 * Asset bytes resolve from the open project's sidecar `assets/` directory first (where import copies
 * them, #141/ADR-021), falling back to the classpath for the bundled sample project's assets. Both key
 * off the asset's project-relative [Asset.path], mirroring the desktop `painterResource("<path>")` that
 * codegen emits. Decodes are cached by asset id so composition doesn't re-read the file; asset ids are
 * unique per document (fresh ULIDs), so a new import never collides with a cached entry.
 */
internal class AssetImageLoader(private val projectDir: () -> Path?, private val assets: () -> List<Asset>) {
    private val cache = HashMap<String, ImageBitmap?>()

    fun load(assetId: String): ImageBitmap? = cache.getOrPut(assetId) {
        val path = assets().firstOrNull { it.id == assetId }?.path ?: return@getOrPut null
        assetBytes(projectDir(), path)?.let { bytes ->
            runCatching { loadImageBitmap(bytes.inputStream()) }.getOrNull()
        }
    }
}

/**
 * Reads an asset's raw bytes by its project-relative [path] (e.g. `assets/hero.png`): from the open
 * project's directory [projectDir] first (imported assets on disk, #141), then the classpath (the
 * bundled sample). Shared by the canvas loader and the export path so both resolve the same source.
 */
internal fun assetBytes(projectDir: Path?, path: String): ByteArray? =
    projectAssetBytes(projectDir, path) ?: classpathAssetBytes(path)

/**
 * Reads [path] from within [projectDir] on disk, or null when there is no project dir, the file is
 * absent, or (defense in depth over the PF-5 load-time check) the resolved path escapes the project dir.
 */
private fun projectAssetBytes(projectDir: Path?, path: String): ByteArray? {
    val root = (projectDir ?: return null).toAbsolutePath().normalize()
    val file = root.resolve(path).normalize()
    if (!file.startsWith(root)) return null
    return runCatching { if (Files.isRegularFile(file)) Files.readAllBytes(file) else null }.getOrNull()
}

/**
 * Reads an asset's raw bytes from the classpath by its project-relative [path], or null when absent —
 * the bundled sample project's assets live under `resources/` (ADR-021).
 */
internal fun classpathAssetBytes(path: String): ByteArray? =
    AssetImageLoader::class.java.getResourceAsStream("/" + path.trimStart('/'))?.use { it.readBytes() }

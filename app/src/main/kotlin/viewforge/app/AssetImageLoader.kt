package viewforge.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import viewforge.model.Asset

/**
 * The editor-side half of the `Image` seam (ARCHITECTURE §3): resolves a node's `ResourceRef` asset id
 * to a decoded [ImageBitmap] for the canvas, keeping all disk/decoding concerns in `:app` so the render
 * layer stays pure (see `RenderContext.imageLoader`).
 *
 * Phase 1 loads assets from the classpath (the bundled sample lives under `resources/`), mirroring the
 * desktop `painterResource("<path>")` that codegen emits (ADR-021): both key off the asset's
 * project-relative [Asset.path]. Decodes are cached by asset id so composition doesn't re-read the file.
 * Importing assets from disk into a user project is a focused follow-up (FEATURES §5).
 */
internal class AssetImageLoader(private val assets: () -> List<Asset>) {
    private val cache = HashMap<String, ImageBitmap?>()

    fun load(assetId: String): ImageBitmap? = cache.getOrPut(assetId) {
        val path = assets().firstOrNull { it.id == assetId }?.path ?: return@getOrPut null
        classpathAssetBytes(path)?.let { bytes -> runCatching { loadImageBitmap(bytes.inputStream()) }.getOrNull() }
    }
}

/**
 * Reads an asset's raw bytes from the classpath by its project-relative [path] (e.g. `assets/hero.png`),
 * or null when absent. Shared by the canvas loader and the export path so both key off the same source
 * — the bundled classpath assets in Phase 1; a project's on-disk asset store once import lands (ADR-021).
 */
internal fun classpathAssetBytes(path: String): ByteArray? =
    AssetImageLoader::class.java.getResourceAsStream("/" + path.trimStart('/'))?.use { it.readBytes() }

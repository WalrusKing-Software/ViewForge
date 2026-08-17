package viewforge.editor.shell

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Import-time validation and normalization of a user-supplied image (#141, SECURITY §7). Image decoders
 * are a classic memory-safety hazard and `.vforge` projects get shared, so every imported file is vetted
 * and rewritten before it is copied into the project:
 *
 * - **AS-1** — a file-size cap and a header-only dimension read (no raster allocated) reject oversized
 *   files and decompression bombs *before* the full decode. Only once the pixel count is known to be
 *   bounded do we decode the raster.
 * - **AS-2** — the format is sniffed from the *content* (the registered [ImageIO] reader), never the file
 *   extension; anything that isn't a supported raster image is refused. The stored file's extension is
 *   then derived from the sniffed format, so a mislabeled file is corrected rather than trusted.
 * - **AS-5** — the image is re-encoded from its decoded raster, which drops all embedded metadata (EXIF
 *   GPS coordinates and the like) — a real privacy leak when a project is committed to a public repo.
 *
 * Pure and Compose-free so it is unit-testable; the [AssetImportController] calls it before the guarded
 * copy.
 */
internal object AssetImport {
    /** Max accepted file size (AS-1). 20 MB comfortably covers UI art without inviting huge uploads. */
    const val MAX_FILE_BYTES: Long = 20L * 1024 * 1024

    /** Max accepted dimension per side, in pixels (AS-1). */
    const val MAX_DIMENSION: Int = 12_000

    /** Max accepted total pixels (AS-1 decompression-bomb guard): ~40 MP, independent of the byte size. */
    const val MAX_PIXELS: Long = 40_000_000L

    /** Sniffed content formats we accept, mapped to the canonical file extension we store them under. */
    private val ALLOWED_FORMATS: Map<String, String> =
        mapOf("png" to "png", "jpeg" to "jpg", "jpg" to "jpg", "gif" to "gif", "bmp" to "bmp")

    /** Human-readable list of accepted formats for the picker and error messages. */
    val ACCEPTED_EXTENSIONS: List<String> = listOf("png", "jpg", "jpeg", "gif", "bmp")

    sealed interface Result {
        /** A vetted, metadata-stripped image: [bytes] re-encoded as [extension], with real [width]/[height]. */
        data class Ok(val bytes: ByteArray, val extension: String, val width: Int, val height: Int) : Result

        /** The file was refused; [reason] is a user-facing explanation. */
        data class Rejected(val reason: String) : Result
    }

    /**
     * Validate [raw] and, if it passes, return a metadata-free re-encoding of it plus its real dimensions
     * and canonical extension. Never decodes an unbounded raster: the size and header-dimension checks run
     * first, so a decompression bomb is rejected before [javax.imageio.ImageReader.read].
     */
    fun validateAndNormalize(raw: ByteArray): Result {
        if (raw.isEmpty()) return Result.Rejected("The file is empty.")
        if (raw.size > MAX_FILE_BYTES) {
            return Result.Rejected("The image is larger than ${MAX_FILE_BYTES / (1024 * 1024)} MB.")
        }

        ImageIO.createImageInputStream(ByteArrayInputStream(raw)).use { iis ->
            if (iis == null) return Result.Rejected("The file could not be read as an image.")
            val readers = ImageIO.getImageReaders(iis)
            if (!readers.hasNext()) {
                return Result.Rejected("The file is not a supported image (${ACCEPTED_EXTENSIONS.joinToString(", ")}).")
            }
            val reader = readers.next()
            try {
                reader.input = iis
                // AS-2: trust the content, not the name. An unrecognized/unsupported format is refused.
                val extension = ALLOWED_FORMATS[reader.formatName.lowercase()]
                    ?: return Result.Rejected("Unsupported image format: ${reader.formatName}.")

                // AS-1: dimensions come from the header only — no raster is allocated yet.
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0) return Result.Rejected("The image has invalid dimensions.")
                if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    return Result.Rejected("The image is larger than ${MAX_DIMENSION}px on a side.")
                }
                if (width.toLong() * height.toLong() > MAX_PIXELS) {
                    return Result.Rejected("The image has too many pixels (possible decompression bomb).")
                }

                // Bounded now: decode the raster and re-encode it, which strips all embedded metadata (AS-5).
                val image = reader.read(0)
                val out = ByteArrayOutputStream()
                if (!ImageIO.write(image, extension, out)) {
                    return Result.Rejected("The image could not be processed.")
                }
                return Result.Ok(out.toByteArray(), extension, width, height)
            } catch (e: Exception) {
                // AS-3: a decode failure is a refusal, never a crash.
                return Result.Rejected("The image could not be read: ${e.message}")
            } finally {
                reader.dispose()
            }
        }
    }
}

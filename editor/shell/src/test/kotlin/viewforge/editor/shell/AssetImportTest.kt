package viewforge.editor.shell

import viewforge.project.GuardedWriter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The image-import filename handling (#141): the sanitize + dedupe logic that turns an arbitrary picked
 * file name into a safe, non-colliding name under the project's `assets/` dir, and a round-trip proving
 * the sanitized name actually passes [GuardedWriter]'s validation and lands confined to the project root.
 * The UI/dialog half of [AssetImportController] is exercised manually; this pins the pure logic.
 */
class AssetImportTest {
    // Resolve to the real path so the confinement check compares like-for-like: on Windows the temp dir
    // can come back as an 8.3 short name, which GuardedWriter's realPath resolution would not match.
    private val tmp: Path = Files.createTempDirectory("vforge-asset-import-test").toRealPath()

    @AfterTest
    fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `sanitize maps unsafe characters and trims to a portable name`() {
        assertEquals("logo.png", sanitizeFileName("logo.png"))
        // Spaces and other characters outside [A-Za-z0-9._-] become underscores.
        assertEquals("my_logo_v2.png", sanitizeFileName("my logo v2.png"))
        // A leading dot (hidden file) and directory parts are stripped.
        assertEquals("hero.png", sanitizeFileName("/some/dir/.hero.png"))
        // Nothing usable falls back to a stable default.
        assertEquals("image", sanitizeFileName("..."))
    }

    @Test
    fun `sanitize never yields a name GuardedWriter rejects (trailing dot)`() {
        assertTrue(!sanitizeFileName("logo.").endsWith("."))
    }

    @Test
    fun `uniqueAssetName suffixes on collision, preserving the extension`() {
        val assets = tmp.resolve("assets").also { it.createDirectories() }
        // First import: the name is free, used as-is.
        assertEquals("logo.png", uniqueAssetName(assets, "logo.png"))

        // With logo.png present, the next collision bumps to -2, then -3.
        Files.createFile(assets.resolve("logo.png"))
        assertEquals("logo-2.png", uniqueAssetName(assets, "logo.png"))
        Files.createFile(assets.resolve("logo-2.png"))
        assertEquals("logo-3.png", uniqueAssetName(assets, "logo.png"))
    }

    @Test
    fun `an extensionless name still dedupes`() {
        val assets = tmp.resolve("assets").also { it.createDirectories() }
        Files.createFile(assets.resolve("image"))
        assertEquals("image-2", uniqueAssetName(assets, "image"))
    }

    @Test
    fun `withExtension replaces or appends the canonical extension`() {
        assertEquals("photo.png", withExtension("photo.jpeg", "png")) // content wins over a lying name (AS-2)
        assertEquals("photo.jpg", withExtension("photo", "jpg"))
    }

    @Test
    fun `the sanitized name copies through GuardedWriter, confined to the project root`() {
        val projectDir = tmp
        val assetsDir = projectDir.resolve("assets")
        val name = uniqueAssetName(assetsDir, "my hero.png")
        val bytes = byteArrayOf(1, 2, 3, 4)

        GuardedWriter.writeBytes(assetsDir.resolve(name), bytes, root = projectDir)

        val written = assetsDir.resolve(name)
        assertEquals("my_hero.png", name)
        assertTrue(Files.isRegularFile(written))
        assertTrue(written.toAbsolutePath().normalize().startsWith(projectDir.toAbsolutePath().normalize()))
        assertTrue(bytes.contentEquals(Files.readAllBytes(written)))
    }

    // --- SECURITY §7 validation (AS-1/AS-2/AS-5) ---------------------------------------------------

    @Test
    fun `a real PNG validates, reporting its content format and dimensions`() {
        val png = encodePng(width = 24, height = 16)

        val result = AssetImport.validateAndNormalize(png)

        val ok = assertIs<AssetImport.Result.Ok>(result)
        assertEquals("png", ok.extension) // sniffed from content, not an extension (AS-2)
        assertEquals(24, ok.width)
        assertEquals(16, ok.height)
        // The re-encoded bytes still decode to the same pixel size (AS-5 re-encode preserved the image).
        val decoded = ImageIO.read(ok.bytes.inputStream())
        assertEquals(24, decoded.width)
        assertEquals(16, decoded.height)
    }

    @Test
    fun `non-image bytes are refused, never decoded`() {
        val result = AssetImport.validateAndNormalize(byteArrayOf(1, 2, 3, 4, 5))
        assertIs<AssetImport.Result.Rejected>(result)
    }

    @Test
    fun `an empty file is refused`() {
        assertIs<AssetImport.Result.Rejected>(AssetImport.validateAndNormalize(ByteArray(0)))
    }

    @Test
    fun `an over-size file is refused before any decode`() {
        val huge = ByteArray((AssetImport.MAX_FILE_BYTES + 1).toInt())
        assertIs<AssetImport.Result.Rejected>(AssetImport.validateAndNormalize(huge))
    }

    @Test
    fun `a decompression-bomb declaring huge dimensions is refused before the raster decode`() {
        // A valid PNG header (signature + IHDR + IEND) declaring dimensions above the pixel cap. getWidth
        // reads the header only, so the bomb is caught (AS-1) before read() would allocate the raster.
        val bomb = pngHeaderOnly(width = 50_000, height = 50_000)
        val result = AssetImport.validateAndNormalize(bomb)
        val rejected = assertIs<AssetImport.Result.Rejected>(result)
        assertTrue(rejected.reason.contains("pixels") || rejected.reason.contains("side"))
    }

    /** A minimal real PNG of the given size, encoded by ImageIO (has a valid IDAT). */
    private fun encodePng(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out)
        return out.toByteArray()
    }

    /**
     * A PNG with only the signature, an IHDR declaring [width]×[height], and IEND — enough for a reader to
     * report dimensions from the header, without a decodable raster. Used to exercise the pre-decode cap.
     */
    private fun pngHeaderOnly(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) // signature
        val ihdr = ByteArrayOutputStream().apply {
            write(intBytes(width))
            write(intBytes(height))
            write(byteArrayOf(8, 6, 0, 0, 0)) // 8-bit depth, RGBA, no compression/filter/interlace
        }.toByteArray()
        writeChunk(out, "IHDR", ihdr)
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.write(intBytes(data.size))
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        out.write(intBytes(crc.value.toInt()))
    }

    private fun intBytes(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}

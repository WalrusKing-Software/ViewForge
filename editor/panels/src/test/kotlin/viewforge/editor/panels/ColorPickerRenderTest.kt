package viewforge.editor.panels

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the color picker body headlessly (skiko is on the test classpath, like [ThemeEditorLayoutTest])
 * to prove it composes and lays out under the real Compose runtime, and that it seeds its preview from the
 * incoming hex (#293). Click-through interaction is not simulated here; the value pipeline (components →
 * normalized hex) is covered by [PropEditingTest]'s `argbToHex` round-trip.
 */
class ColorPickerRenderTest {
    @Test
    fun `panel renders and previews the seeded color`() {
        val image = render(width = 260, height = 400) {
            MaterialTheme { ColorPickerPanel(current = "#2196F3") {} }
        }
        // Something was drawn — the panel (preview, hex field, four sliders, preset grid) composed without throwing.
        assertTrue(anyOpaque(image), "the picker panel drew nothing")
        // The preview strip near the top reflects the seeded blue (#2196F3): blue dominates red and green.
        val (r, g, b) = sample(image, x = 120, y = 26)
        assertTrue(b > 150 && b > r && b > g, "preview should show the seeded blue, was ($r, $g, $b)")
    }

    private fun sample(image: BufferedImage, x: Int, y: Int): Triple<Int, Int, Int> {
        val rgb = image.getRGB(x, y)
        return Triple((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }

    private fun anyOpaque(image: BufferedImage): Boolean {
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if ((image.getRGB(x, y) ushr 24) != 0) return true
            }
        }
        return false
    }

    private fun render(width: Int, height: Int, content: @Composable () -> Unit): BufferedImage {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f), content = content)
        try {
            val png = requireNotNull(scene.render().encodeToData()) { "skia failed to encode the render" }.bytes
            return ImageIO.read(ByteArrayInputStream(png))
        } finally {
            scene.close()
        }
    }
}

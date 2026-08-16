package viewforge.editor.panels

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import viewforge.editor.state.ComponentCatalog
import viewforge.editor.state.EditorState
import viewforge.editor.state.PaletteEntry
import viewforge.model.ColorPair
import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import viewforge.model.Theme
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The theme dialog must size to its content, not to a fixed height that leaves the dark chrome shorter
 * than the window and exposes the native white background below it (#162). The dialog window packs to
 * [ThemeEditorContent], so these assertions on the content's rendered height stand in for the window
 * height: a sparse theme renders shorter than the cap (so the packed window is compact), while a
 * token-heavy theme is clamped to the cap and scrolls rather than growing without bound.
 *
 * `HEIGHT_CAP_PX` mirrors the `heightIn(max = 680.dp)` in [ThemeEditorContent] at density 1.
 */
class ThemeEditorLayoutTest {
    @Test
    fun `a sparse theme renders well under the height cap`() {
        val height = renderedContentHeight(theme = Theme())
        assertTrue(
            height in 1 until HEIGHT_CAP_PX,
            "an empty theme should wrap to less than the ${HEIGHT_CAP_PX}px cap, was ${height}px (#162)",
        )
    }

    @Test
    fun `a token-heavy theme is clamped to the cap and scrolls`() {
        val height = renderedContentHeight(theme = crowdedTheme())
        assertTrue(
            height in (HEIGHT_CAP_PX - 2)..(HEIGHT_CAP_PX + 1),
            "a crowded theme should clamp to the ${HEIGHT_CAP_PX}px cap and scroll, was ${height}px (#162)",
        )
    }

    /**
     * Renders [ThemeEditorContent] onto a scene taller than the cap and returns the height, in pixels, of
     * the drawn content — the extent of the (opaque) dark [androidx.compose.material3.Surface], found by
     * scanning up from the bottom for the last non-transparent row. Everything below the content is the
     * scene's transparent background, so this is exactly the height the packed window would take.
     */
    private fun renderedContentHeight(theme: Theme): Int {
        val png = renderToPng(width = 560, height = HEIGHT_CAP_PX + 80) {
            ThemeEditorContent(stateWith(theme))
        }
        val image = ImageIO.read(ByteArrayInputStream(png))
        for (y in image.height - 1 downTo 0) {
            for (x in 0 until image.width) {
                if ((image.getRGB(x, y) ushr 24) != 0) return y + 1
            }
        }
        return 0
    }

    private fun renderToPng(width: Int, height: Int, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f), content = content)
        try {
            return requireNotNull(scene.render().encodeToData()) { "skia failed to encode the render" }.bytes
        } finally {
            scene.close()
        }
    }

    private fun stateWith(theme: Theme): EditorState = EditorState(
        Project(
            id = "p",
            name = "Theme Sample",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            theme = theme,
            screens = listOf(Screen("s1", "Home", Node(NodeId("root"), "compose.foundation.layout.Column"))),
        ),
        FakeCatalog,
    )

    /** More color tokens than fit in the cap, so the height clamps and the body scrolls. */
    private fun crowdedTheme(): Theme = Theme(
        colors = (1..40).associate { "color$it" to ColorPair("#101010", "#F0F0F0") },
    )

    private object FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.foundation.layout.Column", "Column", "Layout"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = type.startsWith("compose.foundation.layout.")

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private companion object {
        const val HEIGHT_CAP_PX = 680
    }
}

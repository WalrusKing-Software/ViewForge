package viewforge.editor.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import viewforge.editor.state.ComponentCatalog
import viewforge.editor.state.EditorState
import viewforge.editor.state.ExportMode
import viewforge.editor.state.PaletteEntry
import viewforge.editor.state.ProjectExportService
import viewforge.editor.state.RegenerationReport
import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The toolbar must not clip its action buttons at narrow window widths (#161). Before the fix the actions
 * lived in a single [androidx.compose.foundation.layout.Row] that ran off the edge; now they sit in a
 * [androidx.compose.foundation.layout.FlowRow] that wraps to a second line. This renders the real [Toolbar]
 * at a generously wide width and a deliberately cramped one, proving it composes and rasterizes without
 * error in both — a regression guard for the wrapping layout.
 *
 * Set `-Dviewforge.toolbar.dump=<dir>` to also write the two PNGs for visual inspection; unset (the CI
 * default) it asserts only, leaving no artifacts behind.
 */
class ToolbarLayoutTest {
    @Test
    fun `toolbar composes and renders at wide, mid, and narrow widths`() {
        val wide = renderToolbar(widthDp = 1200)
        val mid = renderToolbar(widthDp = 760)
        val narrow = renderToolbar(widthDp = 360)

        assertTrue(wide.isNotEmpty(), "toolbar failed to render at a wide width")
        assertTrue(mid.isNotEmpty(), "toolbar failed to render at a mid (two-row wrap) width (#161)")
        assertTrue(narrow.isNotEmpty(), "toolbar failed to render at a narrow width (#161 wrapping)")

        System.getProperty("viewforge.toolbar.dump")?.let { dir ->
            val out = Path.of(dir)
            java.nio.file.Files.createDirectories(out)
            java.nio.file.Files.write(out.resolve("toolbar-wide.png"), wide)
            java.nio.file.Files.write(out.resolve("toolbar-mid.png"), mid)
            java.nio.file.Files.write(out.resolve("toolbar-narrow.png"), narrow)
        }
    }

    /** Renders just the [Toolbar] at [widthDp] (density 1) and returns the encoded PNG bytes. */
    private fun renderToolbar(widthDp: Int): ByteArray {
        val state = freshState()
        val export = ExportController(state, NoopExportService)
        return renderToPng(width = widthDp, height = 260) {
            MaterialTheme {
                Toolbar(state = state, export = export, onOpenThemeEditor = {})
            }
        }
    }

    private fun renderToPng(width: Int, height: Int, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f), content = content)
        try {
            return requireNotNull(scene.render().encodeToData()) { "skia failed to encode the render" }.bytes
        } finally {
            scene.close()
        }
    }

    private fun freshState(): EditorState = EditorState(
        Project(
            id = "p",
            name = "Truncation Sample",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(
                Screen("s1", "Home", Node(NodeId("root"), "compose.foundation.layout.Column")),
            ),
        ),
        FakeCatalog,
    )

    /** A minimal catalog: one container type is enough to build [EditorState]. */
    private object FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.foundation.layout.Column", "Column", "Layout"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = type.startsWith("compose.foundation.layout.")

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    /** The export seam is never exercised by a render (only by click handlers), so every call is inert. */
    private object NoopExportService : ProjectExportService {
        override fun conflicts(project: Project, dir: Path, mode: ExportMode): List<String> = emptyList()

        override fun export(project: Project, dir: Path, mode: ExportMode): List<String> = emptyList()

        override fun regenerationReport(project: Project, dir: Path): RegenerationReport =
            RegenerationReport(emptyList(), emptyList(), emptyList())

        override fun regenerate(project: Project, dir: Path): RegenerationReport =
            RegenerationReport(emptyList(), emptyList(), emptyList())
    }
}

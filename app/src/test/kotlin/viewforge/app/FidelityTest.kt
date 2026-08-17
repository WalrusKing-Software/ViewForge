package viewforge.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropValue
import viewforge.model.Theme
import viewforge.packages.compose.render.ComposeRenderer
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Canvas/output fidelity (PROJECT_PLAN §8 exit criterion #3, risk 7.4): the interpreted canvas must
 * render the sample screen identically to the idiomatic hand-written composable the codegen emits.
 *
 * Both halves are rendered here in the *same* JVM/Skia environment, under the *same* project theme
 * ([ComposeRenderer.ProjectTheme]), with the *same* image bitmaps — so a pixel diff isolates the one
 * thing under test (does the interpreter draw what the generated code would?) and can't drift with the
 * host OS's font rendering the way a checked-in reference PNG would. The generated `GalleryScreen.kt`
 * text is separately pinned by the golden suite and proven to compile by the compile gate; [GalleryTwin]
 * mirrors it, so interpreter == twin == compiled output.
 */
class FidelityTest {
    private val width = 420
    private val height = 900

    @Test
    fun `interpreter render matches the idiomatic composable pixel-for-pixel`() {
        val project = sampleProject()
        // The sample's assets are bundled on the classpath, so no project dir is needed (classpath fallback).
        val loader = AssetImageLoader(projectDir = { null }, assets = { project.assets })
        val root = project.screens.single().root

        val interpreted = renderToPng {
            ComposeRenderer.RenderScreen(
                root = root,
                theme = project.theme,
                dark = false,
                imageLoader = loader::load,
            )
        }
        val twin = renderToPng {
            ComposeRenderer.ProjectTheme(project.theme, dark = false) { GalleryTwin(loader) }
        }

        assertTrue(
            interpreted.contentEquals(twin),
            "interpreter render diverged from the idiomatic composable (${interpreted.size} vs ${twin.size} bytes)",
        )
    }

    /**
     * A `Text` with no explicit `color` must render the same on the canvas as in generated code, even
     * when the editor chrome hosting the canvas sets an ambient `LocalContentColor` (#155). Codegen omits
     * the `color` arg, so a compiled screen resolves it through `LocalContentColor` — which defaults to
     * black under the generated `AppTheme` (a bare `MaterialTheme`, no `Surface`). The canvas must not
     * instead inherit the surrounding editor's faint content color: [ComposeRenderer.ProjectTheme] pins
     * content color to black at the canvas root, insulating it from whatever ambient the host provides.
     */
    @Test
    fun `unset text color is insulated from the host's ambient content color`() {
        val theme = Theme()
        val root = Node(
            id = NodeId("n_root"),
            type = "compose.foundation.layout.Column",
            children = listOf(
                Node(
                    id = NodeId("n_text"),
                    type = "compose.material3.Text",
                    props = mapOf("text" to PropValue.Literal(JsonPrimitive("Hello"))),
                ),
            ),
        )

        // The canvas as the editor hosts it: an intentionally-wrong ambient content color wraps the render,
        // mimicking the editor chrome's onSurface bleeding in. The fix must override it back to black.
        val interpreted = renderToPng {
            CompositionLocalProvider(LocalContentColor provides Color.Red) {
                ComposeRenderer.RenderScreen(root = root, theme = theme, dark = false)
            }
        }
        // The compiled twin: the same screen under the project theme with no hostile ambient — black text.
        val twin = renderToPng {
            ComposeRenderer.ProjectTheme(theme, dark = false) {
                Column { Text(text = "Hello") }
            }
        }

        assertTrue(
            interpreted.contentEquals(twin),
            "canvas text bled the host's content color instead of codegen's black default (#155)",
        )
    }

    private fun renderToPng(content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f), content = content)
        try {
            return requireNotNull(scene.render().encodeToData()) { "skia failed to encode the render" }.bytes
        } finally {
            scene.close()
        }
    }
}

/** A hand-written mirror of the generated `GalleryScreen.kt` — the "compiled output" side of the diff. */
@Composable
private fun GalleryTwin(loader: AssetImageLoader) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = "Photo Gallery",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleLarge,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Button(onClick = { /* TODO */ }) { Text(text = "Add") }
            Button(onClick = { /* TODO */ }) { Text(text = "Sort") }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            item { GalleryRowTwin(loader, "asset_hero", "Sunset", "Beach at dusk") }
            item { GalleryRowTwin(loader, "asset_icon", "Mountains", "Alpine trail") }
            item {
                Box(
                    modifier = Modifier.size(80.dp).padding(top = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "More coming soon")
                }
            }
        }
    }
}

@Composable
private fun GalleryRowTwin(loader: AssetImageLoader, assetId: String, title: String, subtitle: String) {
    Row(
        modifier = Modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = BitmapPainter(
                requireNotNull(loader.load(assetId)) {
                    "test asset $assetId missing"
                } as ImageBitmap,
            ),
            contentDescription = title,
            modifier = Modifier.size(64.dp),
            contentScale = ContentScale.Crop,
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = subtitle)
        }
    }
}

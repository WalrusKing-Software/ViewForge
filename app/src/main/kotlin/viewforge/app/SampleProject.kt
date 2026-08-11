package viewforge.app

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.Asset
import viewforge.model.ColorPair
import viewforge.model.FrameworkRef
import viewforge.model.ModifierEntry
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.model.Theme

/**
 * The sample document the editor opens on launch — the Phase-1 "something real" (PROJECT_PLAN §8, M9):
 * a photo-gallery screen that exercises every component exit criterion #1 demands — nested
 * `Column`/`Row`/`Box`, `Text`, `Button`s, `Image`s, and a scrollable `LazyColumn` — plus the
 * interesting value paths (ordered modifier chains, `ThemeRef` color/typography, `ResourceRef` image
 * sources, a `RawExpression` `onClick` that must NOT be evaluated on the canvas, PF-4).
 *
 * It is built in code (rather than loaded from a file at runtime) so the app doesn't depend on
 * locating `samples/` on disk; `samples/Gallery.vforge` is its byte-identical serialization, and a
 * test asserts the two stay in lockstep.
 */
internal fun sampleProject(): Project = Project(
    id = "01J8GALLERY",
    name = "Gallery",
    framework = FrameworkRef(packageId = "compose-multiplatform", packageVersion = "1.0.0"),
    targets = listOf("desktop"),
    theme = Theme(colors = mapOf("primary" to ColorPair(light = "#6750A4", dark = "#D0BCFF"))),
    screens = listOf(
        Screen(
            id = "scr_gallery",
            name = "GalleryScreen",
            previewProfile = "desktop_1280x800",
            root = Node(
                id = NodeId("n_root"),
                type = "compose.foundation.layout.Column",
                modifiers = listOf(
                    ModifierEntry(id = "m_fill", type = "compose.fillMaxSize"),
                    ModifierEntry(id = "m_pad", type = "compose.padding", args = mapOf("all" to literal(24))),
                ),
                children = listOf(
                    Node(
                        id = NodeId("n_title"),
                        type = "compose.material3.Text",
                        props = mapOf(
                            "text" to literal("Photo Gallery"),
                            "style" to PropValue.ThemeRef("typography.titleLarge"),
                            "color" to PropValue.ThemeRef("colors.primary"),
                        ),
                    ),
                    Node(
                        id = NodeId("n_actions"),
                        type = "compose.foundation.layout.Row",
                        props = mapOf("horizontalArrangement" to literal("SpaceBetween")),
                        modifiers = listOf(
                            ModifierEntry(id = "m_actions", type = "compose.fillMaxWidth"),
                            ModifierEntry(
                                id = "m_actions_pad",
                                type = "compose.padding",
                                args = mapOf(
                                    "top" to literal(16),
                                ),
                            ),
                        ),
                        children = listOf(button("n_add", "Add"), button("n_sort", "Sort")),
                    ),
                    Node(
                        id = NodeId("n_list"),
                        type = "compose.foundation.lazy.LazyColumn",
                        modifiers = listOf(
                            ModifierEntry(id = "m_list", type = "compose.fillMaxWidth"),
                            ModifierEntry(
                                id = "m_list_pad",
                                type = "compose.padding",
                                args = mapOf("top" to literal(16)),
                            ),
                        ),
                        children = listOf(
                            galleryRow("n_row_a", "asset_hero", "Sunset", "Beach at dusk"),
                            galleryRow("n_row_b", "asset_icon", "Mountains", "Alpine trail"),
                            Node(
                                id = NodeId("n_more"),
                                type = "compose.foundation.layout.Box",
                                props = mapOf("contentAlignment" to literal("Center")),
                                modifiers = listOf(
                                    ModifierEntry(
                                        id = "m_more",
                                        type = "compose.size",
                                        args = mapOf(
                                            "all" to literal(80),
                                        ),
                                    ),
                                    ModifierEntry(
                                        id = "m_more_pad",
                                        type = "compose.padding",
                                        args = mapOf("top" to literal(8)),
                                    ),
                                ),
                                children = listOf(
                                    Node(
                                        id = NodeId("n_more_text"),
                                        type = "compose.material3.Text",
                                        props = mapOf("text" to literal("More coming soon")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    ),
    assets = listOf(
        Asset(id = "asset_hero", type = "image/png", path = "assets/hero.png", originalName = "hero.png"),
        Asset(id = "asset_icon", type = "image/png", path = "assets/icon.png", originalName = "icon.png"),
    ),
)

/** One gallery list row: a thumbnail [Image] beside a title/subtitle [Column] (nested Row → Image + Column). */
private fun galleryRow(id: String, assetId: String, title: String, subtitle: String): Node = Node(
    id = NodeId(id),
    type = "compose.foundation.layout.Row",
    props = mapOf("verticalAlignment" to literal("CenterVertically")),
    modifiers = listOf(
        ModifierEntry(id = "${id}_pad", type = "compose.padding", args = mapOf("all" to literal(8))),
    ),
    children = listOf(
        Node(
            id = NodeId("${id}_img"),
            type = "compose.foundation.Image",
            props = mapOf(
                "source" to PropValue.ResourceRef(assetId),
                "contentDescription" to literal(title),
                "contentScale" to literal("Crop"),
            ),
            modifiers = listOf(
                ModifierEntry(id = "${id}_size", type = "compose.size", args = mapOf("all" to literal(64))),
            ),
        ),
        Node(
            id = NodeId("${id}_text"),
            type = "compose.foundation.layout.Column",
            modifiers = listOf(
                ModifierEntry(id = "${id}_text_pad", type = "compose.padding", args = mapOf("start" to literal(12))),
            ),
            children = listOf(
                Node(
                    id = NodeId("${id}_title"),
                    type = "compose.material3.Text",
                    props = mapOf(
                        "text" to literal(title),
                        "style" to PropValue.ThemeRef("typography.titleMedium"),
                    ),
                ),
                Node(
                    id = NodeId("${id}_sub"),
                    type = "compose.material3.Text",
                    props = mapOf("text" to literal(subtitle)),
                ),
            ),
        ),
    ),
)

/** A button whose label lives in its `content` slot; `onClick` is a raw expression, never run (PF-4). */
private fun button(id: String, label: String): Node = Node(
    id = NodeId(id),
    type = "compose.material3.Button",
    props = mapOf("onClick" to PropValue.RawExpression("{ /* TODO */ }")),
    slots = mapOf(
        "content" to listOf(
            Node(
                id = NodeId("${id}_label"),
                type = "compose.material3.Text",
                props = mapOf("text" to literal(label)),
            ),
        ),
    ),
)

private fun literal(value: String): PropValue = PropValue.Literal(JsonPrimitive(value))

private fun literal(value: Int): PropValue = PropValue.Literal(JsonPrimitive(value))

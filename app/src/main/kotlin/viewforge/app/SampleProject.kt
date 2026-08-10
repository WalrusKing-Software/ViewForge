package viewforge.app

import kotlinx.serialization.json.JsonPrimitive
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
 * The hardcoded IR tree M2 renders (PROJECT_PLAN §8, definition of done). It mirrors
 * `samples/Demo.vforge` — a centered column with a themed title and a button — but is built in code
 * so the milestone doesn't depend on locating a file at runtime.
 *
 * It deliberately exercises the interesting render paths: an ordered modifier chain
 * (`fillMaxSize` → `padding`), a `ThemeRef` color and typography token, a slot (`Button.content`),
 * and a `RawExpression` `onClick` that must NOT be evaluated on the canvas (PF-4).
 */
internal fun sampleProject(): Project = Project(
    id = "01J8XABCDEF",
    name = "Demo",
    framework = FrameworkRef(packageId = "compose-multiplatform", packageVersion = "1.0.0"),
    targets = listOf("desktop"),
    theme = Theme(colors = mapOf("primary" to ColorPair(light = "#6750A4", dark = "#D0BCFF"))),
    screens =
    listOf(
        Screen(
            id = "scr_01",
            name = "HomeScreen",
            previewProfile = "desktop_1280x800",
            root =
            Node(
                id = NodeId("n_01"),
                type = "compose.foundation.layout.Column",
                props =
                mapOf(
                    "horizontalAlignment" to literal("CenterHorizontally"),
                    "verticalArrangement" to literal("Center"),
                ),
                modifiers =
                listOf(
                    ModifierEntry(id = "m_01", type = "compose.fillMaxSize"),
                    ModifierEntry(
                        id = "m_02",
                        type = "compose.padding",
                        args = mapOf("all" to literal(24)),
                    ),
                ),
                children =
                listOf(
                    Node(
                        id = NodeId("n_02"),
                        type = "compose.material3.Text",
                        props =
                        mapOf(
                            "text" to literal("Welcome"),
                            "style" to PropValue.ThemeRef("typography.titleLarge"),
                            "color" to PropValue.ThemeRef("colors.primary"),
                        ),
                    ),
                    Node(
                        id = NodeId("n_03"),
                        type = "compose.material3.Button",
                        props = mapOf("onClick" to PropValue.RawExpression("{ /* TODO */ }")),
                        modifiers =
                        listOf(
                            ModifierEntry(
                                id = "m_03",
                                type = "compose.padding",
                                args = mapOf("top" to literal(16)),
                            ),
                        ),
                        slots =
                        mapOf(
                            "content" to
                                listOf(
                                    Node(
                                        id = NodeId("n_04"),
                                        type = "compose.material3.Text",
                                        props = mapOf("text" to literal("Get started")),
                                    ),
                                ),
                        ),
                    ),
                ),
            ),
        ),
    ),
)

private fun literal(value: String): PropValue = PropValue.Literal(JsonPrimitive(value))

private fun literal(value: Int): PropValue = PropValue.Literal(JsonPrimitive(value))

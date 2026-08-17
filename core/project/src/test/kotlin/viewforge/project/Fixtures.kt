package viewforge.project

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ColorPair
import viewforge.model.ComponentDef
import viewforge.model.FrameworkRef
import viewforge.model.ModifierEntry
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.model.Theme
import viewforge.model.UserComponent

/** Shared test fixtures. [demoProject] is the DATA_MODEL §11 worked example, built in code. */
object Fixtures {
    fun demoProject(): Project = Project(
        id = "01J8XABCDEF",
        name = "Demo",
        framework = FrameworkRef("compose-multiplatform", "1.0.0"),
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
                        "horizontalAlignment" to PropValue.Literal(JsonPrimitive("CenterHorizontally")),
                        "verticalArrangement" to PropValue.Literal(JsonPrimitive("Center")),
                    ),
                    modifiers =
                    listOf(
                        ModifierEntry(id = "m_01", type = "compose.fillMaxSize"),
                        ModifierEntry(
                            id = "m_02",
                            type = "compose.padding",
                            args = mapOf("all" to PropValue.Literal(JsonPrimitive(24))),
                        ),
                    ),
                    children =
                    listOf(
                        Node(
                            id = NodeId("n_02"),
                            type = "compose.material3.Text",
                            props =
                            mapOf(
                                "text" to PropValue.Literal(JsonPrimitive("Welcome")),
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
                                    args = mapOf("top" to PropValue.Literal(JsonPrimitive(16))),
                                ),
                            ),
                            slots =
                            mapOf(
                                "content" to
                                    listOf(
                                        Node(
                                            id = NodeId("n_04"),
                                            type = "compose.material3.Text",
                                            props =
                                            mapOf(
                                                "text" to
                                                    PropValue.Literal(
                                                        JsonPrimitive("Get started"),
                                                    ),
                                            ),
                                        ),
                                    ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** A minimal valid project (defaults everywhere possible). */
    fun minimalProject(): Project =
        Project(id = "01MIN", name = "Min", framework = FrameworkRef("compose-multiplatform", "1.0.0"))

    /** A single chain of [depth] nodes, for depth-limit tests. */
    fun linearTree(depth: Int): Node {
        var node = Node(id = NodeId.random(), type = "leaf")
        repeat(depth - 1) { node = Node(id = NodeId.random(), type = "box", children = listOf(node)) }
        return node
    }

    /** One node with [childCount] leaf children, for node-count tests. */
    fun wideTree(childCount: Int): Node = Node(
        id = NodeId.random(),
        type = "row",
        children = List(childCount) { Node(id = NodeId.random(), type = "leaf") },
    )

    /** A user-component instance node referencing [componentId] (for cycle tests). */
    fun userComponentInstance(componentId: String): Node = Node(
        id = NodeId.random(),
        type = UserComponent.TYPE,
        props = mapOf(UserComponent.COMPONENT_ID_PROP to PropValue.Literal(JsonPrimitive(componentId))),
    )

    fun component(id: String, references: String? = null): ComponentDef = ComponentDef(
        id = id,
        name = id,
        root =
        Node(
            id = NodeId.random(),
            type = "compose.foundation.layout.Box",
            children = references?.let { listOf(userComponentInstance(it)) } ?: emptyList(),
        ),
    )
}

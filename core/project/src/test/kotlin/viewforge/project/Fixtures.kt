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
import viewforge.model.RecordField
import viewforge.model.Repeater
import viewforge.model.SampleValue
import viewforge.model.ScalarType
import viewforge.model.Screen
import viewforge.model.StateField
import viewforge.model.StateType
import viewforge.model.Theme
import viewforge.model.UserComponent
import viewforge.model.scalarRows

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

    /**
     * A schema-v5 project exercising ADR-034 read-only screen state: a scalar [StateField] and a
     * list-of-record one, a scalar [PropValue.StateBinding], and a [Repeater] whose template binds an
     * `item.*` path. The byte-identical serialization is committed as `samples/Dashboard.vforge`; the
     * two are kept in lockstep by a test (as Gallery is with the app's sample), so this is the single
     * in-code source of that fixture.
     */
    fun stateProject(): Project = Project(
        id = "01J8DASHBRD",
        name = "Dashboard",
        framework = FrameworkRef("compose-multiplatform", "1.0.0"),
        targets = listOf("desktop"),
        screens =
        listOf(
            Screen(
                id = "scr_dash",
                name = "Dashboard",
                previewProfile = "desktop_1280x800",
                state =
                listOf(
                    StateField(
                        name = "title",
                        type = StateType.Scalar(ScalarType.STRING),
                        sample = SampleValue.Scalar(JsonPrimitive("Team Dashboard")),
                    ),
                    StateField(
                        name = "online",
                        type = StateType.Scalar(ScalarType.BOOL),
                        sample = SampleValue.Scalar(JsonPrimitive(true)),
                    ),
                    StateField(
                        name = "members",
                        type =
                        StateType.ListOfRecord(
                            listOf(
                                RecordField("name", ScalarType.STRING),
                                RecordField("role", ScalarType.STRING),
                            ),
                        ),
                        sample =
                        scalarRows(
                            listOf(
                                mapOf("name" to JsonPrimitive("Ada"), "role" to JsonPrimitive("Lead")),
                                mapOf("name" to JsonPrimitive("Grace"), "role" to JsonPrimitive("Engineer")),
                            ),
                        ),
                    ),
                ),
                root =
                Node(
                    id = NodeId("n_dash_col"),
                    type = "compose.foundation.layout.Column",
                    children =
                    listOf(
                        Node(
                            id = NodeId("n_dash_title"),
                            type = "compose.material3.Text",
                            props = mapOf("text" to PropValue.StateBinding("title")),
                        ),
                        Repeater.node(
                            sourcePath = "members",
                            id = NodeId("n_dash_repeat"),
                            template =
                            listOf(
                                Node(
                                    id = NodeId("n_dash_member"),
                                    type = "compose.material3.Text",
                                    props = mapOf("text" to PropValue.StateBinding("item.name")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    /**
     * A schema-v5 project exercising **component-local state** (ADR-034 Amendment): a [ComponentDef] carrying
     * its own [Screen]-style [state] — a scalar `heading` and a list-of-record `rows` — whose internal tree
     * binds the scalar and repeats over the list. A screen instantiates it but declares no state of its own,
     * so the component's state is self-contained (resolved against the component, never the screen).
     */
    fun componentStateProject(): Project = Project(
        id = "01J8COMPSTATE",
        name = "ComponentState",
        framework = FrameworkRef("compose-multiplatform", "1.0.0"),
        targets = listOf("desktop"),
        screens =
        listOf(
            Screen(
                id = "scr_host",
                name = "Host",
                root =
                Node(
                    id = NodeId("n_host_col"),
                    type = "compose.foundation.layout.Column",
                    children = listOf(userComponentInstance("cmp_member_card").copy(id = NodeId("n_host_inst"))),
                ),
            ),
        ),
        components =
        listOf(
            ComponentDef(
                id = "cmp_member_card",
                name = "MemberCard",
                root =
                Node(
                    id = NodeId("n_card_col"),
                    type = "compose.foundation.layout.Column",
                    children =
                    listOf(
                        Node(
                            id = NodeId("n_card_heading"),
                            type = "compose.material3.Text",
                            props = mapOf("text" to PropValue.StateBinding("heading")),
                        ),
                        Repeater.node(
                            sourcePath = "rows",
                            id = NodeId("n_card_repeat"),
                            template =
                            listOf(
                                Node(
                                    id = NodeId("n_card_row"),
                                    type = "compose.material3.Text",
                                    props = mapOf("text" to PropValue.StateBinding("item.label")),
                                ),
                            ),
                        ),
                    ),
                ),
                state =
                listOf(
                    StateField(
                        name = "heading",
                        type = StateType.Scalar(ScalarType.STRING),
                        sample = SampleValue.Scalar(JsonPrimitive("Members")),
                    ),
                    StateField(
                        name = "rows",
                        type = StateType.ListOfRecord(listOf(RecordField("label", ScalarType.STRING))),
                        sample =
                        scalarRows(
                            listOf(
                                mapOf("label" to JsonPrimitive("Ada")),
                                mapOf("label" to JsonPrimitive("Grace")),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

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

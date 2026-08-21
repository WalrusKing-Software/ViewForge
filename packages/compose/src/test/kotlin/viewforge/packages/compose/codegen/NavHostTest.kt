package viewforge.packages.compose.codegen

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.Action
import viewforge.model.ComponentDef
import viewforge.model.FrameworkRef
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.model.Theme
import viewforge.model.UserComponent
import viewforge.packages.compose.targets.ComposeEntryPoints
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Screen-to-screen navigation codegen (ADR-039, #214; component forwarding #324). The per-screen lowering
 * (`Navigate` → `onNavigate("id")` + the injected callback param) is pinned by the `Navigation` golden and the
 * compile gate; this covers the host [NavHost] emits, the navigating/non-navigating parameter split, the
 * transitive `navigates` predicate and callback forwarding through user-component instances (#324), and that the
 * exporter entry points render `App()` when the project navigates.
 */
class NavHostTest {
    private fun text(id: String, value: String) =
        Node(NodeId(id), "compose.material3.Text", props = mapOf("text" to PropValue.Literal(JsonPrimitive(value))))

    /** A Button whose onClick navigates to [targetId], with a Text label — the canonical navigating node. */
    private fun navButton(id: String, targetId: String) = Node(
        NodeId(id),
        "compose.material3.Button",
        slots = mapOf("content" to listOf(text("${id}_t", "Go"))),
        handlers = mapOf("onClick" to listOf(Action.Navigate(targetId))),
    )

    private fun column(id: String, vararg children: Node) =
        Node(NodeId(id), "compose.foundation.layout.Column", children = children.toList())

    private val home = Screen("home", "Home", column("h_col", navButton("h_go", "details")))
    private val details = Screen("details", "Details", column("d_col", text("d_t", "Details")))
    private val navProject = Project(
        id = "p",
        name = "Nav",
        framework = FrameworkRef("compose-multiplatform", "1.0.0"),
        screens = listOf(home, details),
    )

    /** A `vforge.userComponent` instance node referencing the component with id [componentId]. */
    private fun instance(id: String, componentId: String) = Node(
        NodeId(id),
        UserComponent.TYPE,
        props = mapOf(UserComponent.COMPONENT_ID_PROP to PropValue.Literal(JsonPrimitive(componentId))),
    )

    // A component whose own tree navigates, and a "hub" screen that merely *instances* it (#324). The hub carries
    // no Navigate handler of its own — it navigates purely transitively, through the instance.
    private val navCard =
        ComponentDef(id = "cmp", name = "NavCard", root = column("c_col", navButton("c_go", "details")))
    private val hub = Screen("hub", "Hub", column("hub_col", instance("hub_card", "cmp")))
    private val cmpProject = navProject.copy(screens = listOf(hub, details), components = listOf(navCard))

    private fun gen(screen: Screen, components: List<ComponentDef> = emptyList()) =
        ComposeCodeGenerator().generateScreen(
            screen,
            Theme(),
            sourceName = "Nav",
            schemaVersion = 6,
            components = components,
        )

    @Test
    fun `navigates is true for a screen with a Navigate handler and false for a target-only screen`() {
        assertTrue(NavHost.navigates(home.root))
        assertFalse(NavHost.navigates(details.root))
        assertTrue(NavHost.projectNavigates(navProject))
    }

    @Test
    fun `a navigating screen gains an onNavigate parameter before modifier and calls it`() {
        val code = gen(home)
        assertContains(code, "fun Home(onNavigate: (String) -> Unit = {}, modifier: Modifier = Modifier)")
        assertContains(code, "onNavigate(\"details\")")
    }

    @Test
    fun `a target-only screen has no onNavigate parameter`() {
        val code = gen(details)
        assertContains(code, "fun Details(modifier: Modifier = Modifier)")
        assertFalse("onNavigate" in code, "a non-navigating screen must not carry the navigation callback")
    }

    @Test
    fun `the App host switches on a remembered current-screen id, wiring onNavigate only where it navigates`() {
        val host = NavHost.appHost(navProject)
        assertContains(host, "fun App()")
        assertContains(host, "var current by remember { mutableStateOf(\"home\") }")
        assertContains(host, "when (current) {")
        assertContains(host, "\"home\" -> Home(onNavigate = { current = it })")
        // A target-only screen is called plainly — it declares no way to leave, faithfully to its IR.
        assertContains(host, "\"details\" -> Details()")
        assertContains(host, "else -> Home(onNavigate = { current = it })")
        // The `by` delegation on `current` needs the property-delegate operators imported.
        assertContains(host, "import androidx.compose.runtime.getValue")
        assertContains(host, "import androidx.compose.runtime.setValue")
    }

    @Test
    fun `navigates is transitive through a user-component instance, only when the components resolve (#324)`() {
        // The hub screen has no Navigate handler of its own; it navigates solely because its instance references
        // a navigating component — and only when that component is supplied for resolution.
        assertTrue(NavHost.navigates(hub.root, listOf(navCard)))
        assertFalse(NavHost.navigates(hub.root), "without the component list the instance cannot be resolved")
        assertTrue(NavHost.projectNavigates(cmpProject))
    }

    @Test
    fun `a Navigate inside a user component forwards onNavigate through the instance instead of failing (#324)`() {
        val files = ComposeCodeGenerator().generate(cmpProject)
        val card = files.first { it.path == "NavCard.kt" }.content
        val hubScreen = files.first { it.path == "Hub.kt" }.content
        // The component gains its own onNavigate param and lowers Navigate onto it.
        assertContains(card, "fun NavCard(onNavigate: (String) -> Unit = {}, modifier: Modifier = Modifier)")
        assertContains(card, "onNavigate(\"details\")")
        // The instancing screen navigates transitively: it too carries onNavigate, and forwards it to the instance.
        assertContains(hubScreen, "fun Hub(onNavigate: (String) -> Unit = {}, modifier: Modifier = Modifier)")
        assertContains(hubScreen, "NavCard(onNavigate = onNavigate)")
    }

    @Test
    fun `the desktop and android entry points render App() when the project navigates`() {
        assertContains(ComposeEntryPoints.desktopMain(navProject, themed = false), "App()")
        assertContains(ComposeEntryPoints.androidMainActivity(navProject, themed = false), "App()")
    }

    @Test
    fun `a non-navigating project renders its first screen directly, with no App host`() {
        val plain = navProject.copy(screens = listOf(details)) // details never navigates
        assertFalse(NavHost.projectNavigates(plain))
        val main = ComposeEntryPoints.desktopMain(plain, themed = false)
        assertContains(main, "Details()")
        assertFalse("App()" in main, "a non-navigating project must not route through the host")
    }
}

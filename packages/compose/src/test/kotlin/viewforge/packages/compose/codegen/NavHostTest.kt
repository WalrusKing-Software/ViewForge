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
import viewforge.packages.compose.targets.ComposeEntryPoints
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Screen-to-screen navigation codegen (ADR-039, #214). The per-screen lowering (`Navigate` → `onNavigate("id")`
 * + the injected callback param) is pinned by the `Navigation` golden and the compile gate; this covers the
 * host [NavHost] emits, the navigating/non-navigating parameter split, the fail-loud component guard (#324),
 * and that the exporter entry points render `App()` when the project navigates.
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

    private fun gen(screen: Screen) =
        ComposeCodeGenerator().generateScreen(screen, Theme(), sourceName = "Nav", schemaVersion = 6)

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
    fun `a Navigate inside a user component fails loud rather than emitting a call to a missing parameter (#324)`() {
        val component = ComponentDef(
            id = "cmp",
            name = "NavCard",
            root = column("c_col", navButton("c_go", "details")),
        )
        val project = navProject.copy(components = listOf(component))
        assertFailsWith<CodegenException> { ComposeCodeGenerator().generate(project) }
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

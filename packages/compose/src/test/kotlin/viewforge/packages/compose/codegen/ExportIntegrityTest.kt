package viewforge.packages.compose.codegen

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ComponentDef
import viewforge.model.FrameworkRef
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.model.UserComponent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The referential-completeness export gate (#212/#338): [ComposeCodeGenerator.generate] refuses a project that
 * instances a component it does not contain, reporting *every* offending instance with its location at once,
 * rather than the emitter's first-error throw deep inside one screen. A referentially-complete project is
 * unaffected — every used component is emitted as before.
 */
class ExportIntegrityTest {
    private fun instance(nodeId: String, componentId: String) = Node(
        NodeId(nodeId),
        UserComponent.TYPE,
        props = mapOf(UserComponent.COMPONENT_ID_PROP to PropValue.Literal(JsonPrimitive(componentId))),
    )

    private fun column(id: String, vararg children: Node) =
        Node(NodeId(id), "compose.foundation.layout.Column", children = children.toList())

    private fun project(screens: List<Screen>, components: List<ComponentDef> = emptyList()) = Project(
        id = "p",
        name = "Demo",
        framework = FrameworkRef("compose-multiplatform", "1.0.0"),
        screens = screens,
        components = components,
    )

    @Test
    fun `generate refuses a project with a dangling reference and names its location`() {
        val p = project(screens = listOf(Screen("home", "Home", column("h", instance("card", "ghost")))))
        val message = assertFailsWith<CodegenException> { ComposeCodeGenerator().generate(p) }.message!!
        assertContains(message, "Cannot export")
        assertContains(message, "screen 'Home'")
        assertContains(message, "missing component 'ghost'")
        assertContains(message, "card") // the offending node id, so the user can find it
    }

    @Test
    fun `generate reports every dangling reference at once, not just the first`() {
        val p = project(
            screens = listOf(
                Screen("s1", "One", column("c1", instance("n1", "ghost1"))),
                Screen("s2", "Two", column("c2", instance("n2", "ghost2"))),
            ),
        )
        val message = assertFailsWith<CodegenException> { ComposeCodeGenerator().generate(p) }.message!!
        assertContains(message, "2 unresolved user-component reference(s)")
        assertContains(message, "ghost1")
        assertContains(message, "ghost2")
    }

    @Test
    fun `generate succeeds and emits every component when the project is referentially complete`() {
        val card = ComponentDef(id = "cmp", name = "Card", root = column("cr"))
        val p = project(
            screens = listOf(Screen("home", "Home", column("h", instance("i", "cmp")))),
            components = listOf(card),
        )
        val files = ComposeCodeGenerator().generate(p).map { it.path }
        assertTrue("Home.kt" in files && "Card.kt" in files, "expected both screen and component files, got $files")
    }
}

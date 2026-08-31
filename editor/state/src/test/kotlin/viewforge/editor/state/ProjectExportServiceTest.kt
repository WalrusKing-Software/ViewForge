package viewforge.editor.state

import viewforge.model.FrameworkRef
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.Screen
import viewforge.model.UserComponent
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The [ProjectExportService.exportBlockers] default (#212/#338): the seam's shared, framework-free reason a
 * project cannot be exported — every dangling `vforge.userComponent` reference, located — surfaced by the shell
 * before it picks a destination. Exercised through a minimal binding that overrides only the abstract members,
 * so the default itself is under test.
 */
class ProjectExportServiceTest {
    private object Service : ProjectExportService {
        override fun conflicts(project: Project, dir: Path, mode: ExportMode): List<String> = emptyList()
        override fun export(project: Project, dir: Path, mode: ExportMode): List<String> = emptyList()
        override fun regenerationReport(project: Project, dir: Path) =
            RegenerationReport(emptyList(), emptyList(), emptyList())

        override fun regenerate(project: Project, dir: Path) = RegenerationReport(emptyList(), emptyList(), emptyList())
    }

    private fun project(vararg screens: Screen) = Project(
        id = "p",
        name = "Demo",
        framework = FrameworkRef("compose-multiplatform", "1.0.0"),
        screens = screens.toList(),
    )

    private fun column(id: String, vararg children: Node) =
        Node(NodeId(id), "compose.foundation.layout.Column", children = children.toList())

    @Test
    fun `exportBlockers is empty for a referentially-complete project`() {
        assertEquals(emptyList(), Service.exportBlockers(project(Screen("s", "Home", column("c")))))
    }

    @Test
    fun `exportBlockers reports each dangling reference with its location`() {
        val screen = Screen("home", "Home", column("c", UserComponent.instance("ghost", NodeId("inst"))))
        val message = Service.exportBlockers(project(screen)).single()
        assertTrue(message.contains("screen 'Home'"), message)
        assertTrue(message.contains("missing component 'ghost'"), message)
        assertTrue(message.contains("inst"), message) // the offending node id, so the user can find it
    }
}

package viewforge.editor.shell

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
import viewforge.model.UserComponent
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The export controller refuses a referentially-broken project up-front (#212/#338): [ExportController.start]
 * consults [ProjectExportService.exportBlockers] and, when non-empty, records them and returns *before* prompting
 * for a destination — so a dangling `vforge.userComponent` reference never reaches a directory picker or codegen.
 */
class ExportControllerTest {
    @Test
    fun `start records the blockers and does not proceed when the project has a dangling reference`() {
        val controller = ExportController(danglingState(), NoopExportService)
        controller.start(ExportMode.LOOSE_FILES)
        val blockers = controller.exportBlockers
        assertTrue(blockers != null && blockers.isNotEmpty(), "a dangling reference must block export")
        assertTrue(blockers!!.single().contains("missing component 'ghost'"), blockers.single())
        assertNull(controller.pending, "no overwrite confirmation should be pending for a blocked export")
    }

    /** A one-screen project whose only child instances a component the project does not contain. */
    private fun danglingState(): EditorState = EditorState(
        Project(
            id = "p",
            name = "Demo",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(
                Screen(
                    "home",
                    "Home",
                    Node(
                        NodeId("root"),
                        "compose.foundation.layout.Column",
                        children = listOf(UserComponent.instance("ghost", NodeId("inst"))),
                    ),
                ),
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

    /** Inherits the real `exportBlockers` default; the other members are inert (never reached on a blocked run). */
    private object NoopExportService : ProjectExportService {
        override fun conflicts(project: Project, dir: Path, mode: ExportMode): List<String> = emptyList()
        override fun export(project: Project, dir: Path, mode: ExportMode): List<String> = emptyList()
        override fun regenerationReport(project: Project, dir: Path) =
            RegenerationReport(emptyList(), emptyList(), emptyList())

        override fun regenerate(project: Project, dir: Path) = RegenerationReport(emptyList(), emptyList(), emptyList())
    }
}

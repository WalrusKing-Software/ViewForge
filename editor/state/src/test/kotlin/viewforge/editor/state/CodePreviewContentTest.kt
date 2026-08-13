package viewforge.editor.state

import viewforge.model.ComponentDef
import viewforge.model.FrameworkRef
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.Screen
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure code-preview resolver [previewContent] (G3, #50/#69): it turns a generator call into the
 * panel's displayable [PreviewContent], dispatching on the [PreviewTarget] (screen or open component) and
 * wrapping any generation failure into a visible message rather than letting it throw (CLAUDE.md: fail
 * loudly, never blank the panel). Tested without a composition — the seam is a fake.
 */
class CodePreviewContentTest {
    private val screen = Screen("s1", "Home", Node(NodeId("root"), "compose.foundation.layout.Column"))
    private val component = ComponentDef("c1", "PrimaryButton", root = Node(NodeId("cr"), "compose.material3.Button"))
    private val project = Project(id = "p", name = "P", framework = FrameworkRef("compose-multiplatform", "1.0.0"))

    private fun service(
        onScreen: (Project, Screen) -> PreviewSource = { _, _ -> error("previewScreen should not be called") },
        onComponent: (
            Project,
            ComponentDef,
        ) -> PreviewSource = { _, _ -> error("previewComponent should not be called") },
    ) = object : CodePreviewService {
        override fun previewScreen(project: Project, screen: Screen): PreviewSource = onScreen(project, screen)

        override fun previewComponent(project: Project, component: ComponentDef): PreviewSource =
            onComponent(project, component)
    }

    @Test
    fun `a screen target generates the screen source`() {
        val content =
            previewContent(
                service(onScreen = { _, _ ->
                    PreviewSource("SCREEN", emptyMap())
                }),
                project,
                PreviewTarget.OfScreen(screen),
            )
        assertEquals(PreviewContent.Source("SCREEN"), content)
    }

    @Test
    fun `a component target generates the component source (follows the open component)`() {
        val content =
            previewContent(
                service(onComponent = { _, _ ->
                    PreviewSource("COMPONENT", emptyMap())
                }),
                project,
                PreviewTarget.OfComponent(component),
            )
        assertEquals(PreviewContent.Source("COMPONENT"), content)
    }

    @Test
    fun `the node span map is carried through to the Source`() {
        val spans = mapOf("root" to (10 until 40))
        val content = previewContent(
            service(onScreen = { _, _ -> PreviewSource("SCREEN", spans) }),
            project,
            PreviewTarget.OfScreen(screen),
        )
        assertEquals(PreviewContent.Source("SCREEN", spans), content)
    }

    @Test
    fun `a thrown generation error becomes a Failure carrying its message`() {
        val content = previewContent(
            service(onScreen = { _, _ -> throw IllegalStateException("boom") }),
            project,
            PreviewTarget.OfScreen(screen),
        )
        assertEquals(PreviewContent.Failure("boom"), content)
    }

    @Test
    fun `a message-less exception falls back to its type name`() {
        val content = previewContent(
            service(onScreen = { _, _ -> throw IllegalStateException() }),
            project,
            PreviewTarget.OfScreen(screen),
        )
        assertEquals(PreviewContent.Failure("IllegalStateException"), content)
    }

    @Test
    fun `a null target is a Failure and never calls the service`() {
        val content = previewContent(service(), project, target = null)
        assertEquals(PreviewContent.Failure("No screen to preview."), content)
    }
}

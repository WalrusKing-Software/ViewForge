package viewforge.editor.state

import viewforge.model.FrameworkRef
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.Screen
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure code-preview resolver [previewContent] (G3, #50): it turns a generator call into the panel's
 * displayable [PreviewContent], wrapping any generation failure into a visible message rather than
 * letting it throw (CLAUDE.md: fail loudly, never blank the panel). Tested without a composition — the
 * seam is a fake.
 */
class CodePreviewContentTest {
    private val screen = Screen("s1", "Home", Node(NodeId("root"), "compose.foundation.layout.Column"))
    private val project = Project(id = "p", name = "P", framework = FrameworkRef("compose-multiplatform", "1.0.0"))

    private fun service(body: (Project, Screen) -> String) = object : CodePreviewService {
        override fun previewScreen(project: Project, screen: Screen): String = body(project, screen)
    }

    @Test
    fun `a successful generation becomes Source`() {
        val content = previewContent(service { _, _ -> "GENERATED" }, project, screen)
        assertEquals(PreviewContent.Source("GENERATED"), content)
    }

    @Test
    fun `a thrown generation error becomes a Failure carrying its message`() {
        val content = previewContent(service { _, _ -> throw IllegalStateException("boom") }, project, screen)
        assertEquals(PreviewContent.Failure("boom"), content)
    }

    @Test
    fun `a message-less exception falls back to its type name`() {
        val content = previewContent(service { _, _ -> throw IllegalStateException() }, project, screen)
        assertEquals(PreviewContent.Failure("IllegalStateException"), content)
    }

    @Test
    fun `a null screen is a Failure and never calls the service`() {
        val content = previewContent(service { _, _ -> error("should not be called") }, project, screen = null)
        assertEquals(PreviewContent.Failure("No screen to preview."), content)
    }
}

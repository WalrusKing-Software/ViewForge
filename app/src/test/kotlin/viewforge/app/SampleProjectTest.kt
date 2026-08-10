package viewforge.app

import viewforge.project.ProjectValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hardcoded document M2 renders must be a structurally valid project — the same invariants a
 * loaded `.vforge` is held to (SECURITY §3). If the sample can't pass validation it isn't a fair
 * demonstration that the canvas renders real IR.
 */
class SampleProjectTest {
    @Test
    fun `sample project passes structural validation`() {
        ProjectValidator.validate(sampleProject()) // throws on any violation
    }

    @Test
    fun `sample has one screen rooted in a Column with a themed title and a button`() {
        val project = sampleProject()
        assertEquals(1, project.screens.size)

        val root = project.screens.single().root
        assertEquals("compose.foundation.layout.Column", root.type)
        // fillMaxSize before padding — order is semantic (ADR-005).
        assertEquals(listOf("compose.fillMaxSize", "compose.padding"), root.modifiers.map { it.type })

        val types = root.children.map { it.type }
        assertTrue("compose.material3.Text" in types)
        assertTrue("compose.material3.Button" in types)

        val button = root.children.single { it.type == "compose.material3.Button" }
        assertEquals(1, button.slots["content"]?.size, "button label lives in the content slot")
    }
}

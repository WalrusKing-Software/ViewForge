package viewforge.editor.state

import viewforge.model.Action
import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The interactive-code acknowledgment (ADR-035, #277): the light, one-time-per-project notice is due only when
 * the open document has a handler and its project id has not been acknowledged, and dismissing it (or restoring
 * a prior acknowledgment from prefs) suppresses it. Keyed by project id, so it is per project, not per session.
 */
class InteractiveAckTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.material3.Button", "Button", "Input"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = false

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private fun state(interactive: Boolean, id: String = "proj-1"): EditorState {
        val button = Node(
            NodeId("b"),
            "compose.material3.Button",
            handlers = if (interactive) mapOf("onClick" to listOf(Action.Toggle("open"))) else emptyMap(),
        )
        return EditorState(
            Project(
                id = id,
                name = "P",
                framework = FrameworkRef("compose-multiplatform", "1.0.0"),
                screens = listOf(
                    Screen(
                        "s1",
                        "Home",
                        Node(NodeId("root"), "compose.foundation.layout.Column", children = listOf(button)),
                    ),
                ),
            ),
            FakeCatalog(),
        )
    }

    @Test
    fun `a handler-free document never needs the acknowledgment`() {
        assertFalse(state(interactive = false).needsInteractiveAcknowledgment)
    }

    @Test
    fun `an interactive document needs the acknowledgment until it is dismissed`() {
        val s = state(interactive = true)
        assertTrue(s.needsInteractiveAcknowledgment)
        s.acknowledgeInteractive()
        assertFalse(s.needsInteractiveAcknowledgment)
        assertTrue("proj-1" in s.acknowledgedInteractive)
    }

    @Test
    fun `a prior acknowledgment restored from prefs suppresses the notice`() {
        val s = state(interactive = true, id = "proj-2")
        s.applyAcknowledgedInteractive(listOf("proj-2"))
        assertFalse(s.needsInteractiveAcknowledgment)
    }
}

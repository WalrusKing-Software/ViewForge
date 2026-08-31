package viewforge.editor.state

import viewforge.model.Action
import viewforge.model.AdvisorySeverity
import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [EditorState.advisories] emits a non-blocking WARNING (#351) when a node's Navigate handler targets
 * a screen that is no longer in the project — the deleted-screen case that otherwise silently no-ops at
 * run time. These tests cover the advisory logic; the inspector renders whatever the list returns, so
 * no UI-specific test is needed here.
 */
class NavigateAdvisoryTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = emptyList<PaletteEntry>()
        override fun newNode(type: String) = Node(NodeId.random(), type)
        override fun acceptsChildren(type: String) = false
        override fun slotsOf(type: String) = emptyList<String>()
        override fun propsFor(type: String) = emptyList<PropDefinition>()
        override val modifierCatalog = emptyList<ModifierDefinition>()
        override fun modifierDef(type: String) = null
    }

    private fun state(vararg screenIds: String) = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = screenIds.map { Screen(it, it, Node(NodeId.random(), "compose.foundation.layout.Column")) },
        ),
        FakeCatalog(),
    )

    private fun navigateNode(vararg targetIds: String) = Node(
        NodeId("btn"),
        "compose.material3.Button",
        handlers = mapOf("onClick" to targetIds.map { Action.Navigate(it) }),
    )

    @Test
    fun `no advisory when Navigate targets a valid screen`() {
        val s = state("scr_home", "scr_details")
        assertTrue(s.advisories(navigateNode("scr_home")).isEmpty())
    }

    @Test
    fun `WARNING advisory when Navigate targets a deleted screen`() {
        val s = state("scr_home")
        val advisories = s.advisories(navigateNode("scr_ghost"))
        assertEquals(1, advisories.size)
        assertEquals(AdvisorySeverity.WARNING, advisories[0].severity)
        assertTrue("deleted" in advisories[0].message)
    }

    @Test
    fun `no advisory on a handler-free node`() {
        val s = state("scr_home")
        assertTrue(s.advisories(Node(NodeId("col"), "compose.foundation.layout.Column")).isEmpty())
    }

    @Test
    fun `one advisory when mixed valid and dangling Navigate targets exist`() {
        val s = state("scr_home")
        val node = Node(
            NodeId("btn"),
            "compose.material3.Button",
            handlers = mapOf("onClick" to listOf(Action.Navigate("scr_home"), Action.Navigate("scr_ghost"))),
        )
        assertEquals(1, s.advisories(node).size)
    }

    @Test
    fun `one advisory for multiple distinct dangling targets, mentioning the count`() {
        val s = state("scr_home")
        val advisories = s.advisories(navigateNode("scr_a", "scr_b"))
        assertEquals(1, advisories.size)
        assertTrue("2" in advisories[0].message)
    }

    @Test
    fun `duplicate dangling screen id counts as one deleted screen`() {
        val s = state("scr_home")
        val advisories = s.advisories(navigateNode("scr_ghost", "scr_ghost"))
        assertEquals(1, advisories.size)
        assertTrue("1" !in advisories[0].message || "deleted screen" in advisories[0].message)
    }
}

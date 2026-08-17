package viewforge.editor.state

import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import viewforge.prefs.RecentProjects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor's live recent-projects list (D8, #88): applied from prefs at launch and updated on open/save,
 * always most-recent-first, de-duplicated and capped (it delegates to the shared [RecentProjects] rules).
 */
class RecentProjectsStateTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.material3.Text", "Text", "Content"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = false

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private fun state(): EditorState = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(Screen("s1", "Home", Node(NodeId("root"), "compose.material3.Text"))),
        ),
        FakeCatalog(),
    )

    @Test
    fun `starts empty`() {
        assertTrue(state().recentProjects.isEmpty())
    }

    @Test
    fun `applyRecentProjects sanitizes the restored list`() {
        val s = state()
        s.applyRecentProjects(listOf("/a.vforge", "", "/a.vforge", "/b.vforge"))
        assertEquals(listOf("/a.vforge", "/b.vforge"), s.recentProjects)
    }

    @Test
    fun `noteRecentProject promotes to the front and de-duplicates`() {
        val s = state()
        s.noteRecentProject("/a.vforge")
        s.noteRecentProject("/b.vforge")
        s.noteRecentProject("/a.vforge") // re-opening a promotes, not duplicates
        assertEquals(listOf("/a.vforge", "/b.vforge"), s.recentProjects)
    }

    @Test
    fun `noteRecentProject caps the list`() {
        val s = state()
        (1..(RecentProjects.MAX + 3)).forEach { s.noteRecentProject("/p$it.vforge") }
        assertEquals(RecentProjects.MAX, s.recentProjects.size)
        assertEquals("/p${RecentProjects.MAX + 3}.vforge", s.recentProjects.first()) // newest first
    }

    @Test
    fun `remove and clear`() {
        val s = state()
        s.applyRecentProjects(listOf("/a.vforge", "/b.vforge"))
        s.removeRecentProject("/a.vforge")
        assertEquals(listOf("/b.vforge"), s.recentProjects)
        s.clearRecentProjects()
        assertTrue(s.recentProjects.isEmpty())
    }
}

package viewforge.editor.state

import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Palette favorites + recents (P5a, #121): pinning is a persisted per-user set keyed by [PaletteEntry.key];
 * recents are session-only, recorded on insert, most-recent-first and capped. Both resolve against the live
 * palette, dropping keys that no longer exist. A [FakeCatalog] stands in for the Compose package so these
 * run without a composition.
 */
class PaletteFavoritesTest {
    private val text = PaletteEntry("compose.material3.Text", "Text", "Content")
    private val column = PaletteEntry("compose.foundation.layout.Column", "Column", "Layout")

    private class FakeCatalog(override val palette: List<PaletteEntry>) : ComponentCatalog {
        override fun newNode(type: String): Node = Node(NodeId.random(), type)
        override fun acceptsChildren(type: String): Boolean = type.startsWith("compose.foundation.layout.")
        override fun slotsOf(type: String): List<String> = emptyList()
        override fun propsFor(type: String): List<PropDefinition> = emptyList()
        override val modifierCatalog: List<ModifierDefinition> = emptyList()
        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private val root = Node(NodeId("root"), "compose.foundation.layout.Column")

    private fun state(palette: List<PaletteEntry> = listOf(column, text)): EditorState = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(Screen("s1", "Home", root)),
        ),
        FakeCatalog(palette),
    )

    @Test
    fun `toggleFavorite pins and unpins an entry`() {
        val s = state()
        assertFalse(s.isFavorite(text))
        s.toggleFavorite(text)
        assertTrue(s.isFavorite(text))
        assertEquals(listOf(text.key), s.favoriteComponents)
        s.toggleFavorite(text)
        assertFalse(s.isFavorite(text))
        assertTrue(s.favoriteComponents.isEmpty())
    }

    @Test
    fun `favoriteEntries resolve in starring order and drop stale keys`() {
        val s = state()
        // A key with no matching palette entry (e.g. a user component from another project) is ignored.
        s.applyFavoriteComponents(listOf("ghost", text.key, column.key))
        assertEquals(listOf("Text", "Column"), s.favoriteEntries.map { it.label })
    }

    @Test
    fun `inserting records the entry as recent, most-recent-first and de-duplicated`() {
        val s = state()
        s.addFromPalette(text)
        s.addFromPalette(column)
        s.addFromPalette(text) // re-inserting promotes Text back to the front without duplicating it
        assertEquals(listOf(text.key, column.key), s.recentComponents)
        assertEquals(listOf("Text", "Column"), s.recentEntries.map { it.label })
    }

    @Test
    fun `recentComponents is capped at the most recent entries`() {
        val s = state()
        // Insert more distinct types than the cap; the oldest fall off. The types need not be in the palette
        // to be inserted (FakeCatalog builds a node for any type) — recentComponents holds the raw keys.
        (1..EditorState.MAX_RECENT_COMPONENTS + 1).forEach { s.addFromPalette("t$it") }
        assertEquals(EditorState.MAX_RECENT_COMPONENTS, s.recentComponents.size)
        assertEquals("t${EditorState.MAX_RECENT_COMPONENTS + 1}", s.recentComponents.first()) // newest
        assertFalse("t1" in s.recentComponents) // the oldest was dropped
    }

    @Test
    fun `recentEntries drop keys that are not in the palette`() {
        val s = state()
        s.addFromPalette("compose.material3.Text") // resolves
        s.addFromPalette("ghostType") // recorded, but not a palette entry
        assertTrue("ghostType" in s.recentComponents)
        assertEquals(listOf("Text"), s.recentEntries.map { it.label })
    }
}

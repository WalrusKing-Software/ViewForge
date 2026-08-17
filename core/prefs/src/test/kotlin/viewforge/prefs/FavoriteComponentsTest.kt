package viewforge.prefs

import kotlin.test.Test
import kotlin.test.assertEquals

/** The pure palette-favorites list operations (P5a, #121): a toggled set kept in starring order, uncapped. */
class FavoriteComponentsTest {
    @Test
    fun `toggled appends a new key preserving starring order`() {
        assertEquals(listOf("a", "b"), FavoriteComponents.toggled(listOf("a"), "b"))
    }

    @Test
    fun `toggled removes a key that is already pinned`() {
        assertEquals(listOf("a", "c"), FavoriteComponents.toggled(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `toggling the same key twice is a no-op`() {
        val once = FavoriteComponents.toggled(listOf("a"), "b")
        assertEquals(listOf("a"), FavoriteComponents.toggled(once, "b"))
    }

    @Test
    fun `sanitized drops blanks and duplicates but keeps order and does not cap`() {
        val messy = listOf("a", "", "a", "b", "  ", "c")
        assertEquals(listOf("a", "b", "c"), FavoriteComponents.sanitized(messy))
    }

    @Test
    fun `sanitized keeps a long list uncapped, unlike recent projects`() {
        val many = (1..50).map { "k$it" }
        assertEquals(many, FavoriteComponents.sanitized(many))
    }
}

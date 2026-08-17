package viewforge.prefs

import kotlin.test.Test
import kotlin.test.assertEquals

/** The pure recent-projects list operations (D8, #88): most-recent-first, de-duplicated, capped. */
class RecentProjectsTest {
    @Test
    fun `updated puts the path at the front`() {
        assertEquals(listOf("b", "a"), RecentProjects.updated(listOf("a"), "b"))
    }

    @Test
    fun `updated promotes an existing path without duplicating it`() {
        assertEquals(listOf("a", "c", "b"), RecentProjects.updated(listOf("c", "b", "a"), "a"))
    }

    @Test
    fun `updated caps the list, dropping the oldest`() {
        val current = (1..RecentProjects.MAX).map { "p$it" } // p1 (newest) .. pMAX (oldest)
        val result = RecentProjects.updated(current, "new")
        assertEquals(RecentProjects.MAX, result.size)
        assertEquals("new", result.first())
        assertEquals("p${RecentProjects.MAX - 1}", result.last()) // pMAX (the oldest) fell off
    }

    @Test
    fun `sanitized drops blanks and duplicates and caps`() {
        val messy = listOf("a", "", "a", "b", "  ") + (1..RecentProjects.MAX).map { "x$it" }
        val result = RecentProjects.sanitized(messy)
        assertEquals(RecentProjects.MAX, result.size)
        assertEquals(result.distinct(), result)
        assertEquals(listOf("a", "b"), result.take(2))
    }
}

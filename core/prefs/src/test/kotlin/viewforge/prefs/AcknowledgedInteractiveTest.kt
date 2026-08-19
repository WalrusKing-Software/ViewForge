package viewforge.prefs

import kotlin.test.Test
import kotlin.test.assertEquals

/** The pure acknowledged-interactive-projects operations (ADR-035, #277): an idempotent, uncapped id set. */
class AcknowledgedInteractiveTest {
    @Test
    fun `acknowledged appends a new id preserving order`() {
        assertEquals(listOf("a", "b"), AcknowledgedInteractive.acknowledged(listOf("a"), "b"))
    }

    @Test
    fun `acknowledging the same id twice is a no-op`() {
        val once = AcknowledgedInteractive.acknowledged(listOf("a"), "b")
        assertEquals(listOf("a", "b"), AcknowledgedInteractive.acknowledged(once, "b"))
    }

    @Test
    fun `acknowledging a blank id is ignored`() {
        assertEquals(listOf("a"), AcknowledgedInteractive.acknowledged(listOf("a"), "  "))
    }

    @Test
    fun `sanitized drops blanks and duplicates, keeping order`() {
        assertEquals(listOf("a", "b"), AcknowledgedInteractive.sanitized(listOf("a", "", "a", "b", "  ")))
    }
}

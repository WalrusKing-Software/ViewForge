package viewforge.editor.shell

import viewforge.prefs.EditorPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Preferences dialog's inline validation (S5, #105, I8): the pure accept/reject rule behind the numeric
 * fields, tested without a composition. A null result means the value is acceptable and Save may write it.
 */
class PreferencesValidationTest {
    @Test
    fun `a whole number inside the range is accepted`() {
        assertNull(intRangeError("42", 2, 600))
        assertNull(intRangeError("  42  ", 2, 600)) // surrounding whitespace is tolerated
    }

    @Test
    fun `the inclusive bounds are accepted`() {
        assertNull(intRangeError("2", 2, 600))
        assertNull(intRangeError("600", 2, 600))
    }

    @Test
    fun `non-numbers and out-of-range values are rejected`() {
        assertNotNull(intRangeError("", 2, 600))
        assertNotNull(intRangeError("abc", 2, 600))
        assertNotNull(intRangeError("3.5", 2, 600)) // not a whole number
        assertNotNull(intRangeError("1", 2, 600)) // below min
        assertNotNull(intRangeError("601", 2, 600)) // above max
    }

    @Test
    fun `the field helpers use the preference bounds`() {
        assertNull(autosaveIntervalError(EditorPreferences.MIN_AUTOSAVE_INTERVAL_SECONDS.toString()))
        assertNotNull(autosaveIntervalError((EditorPreferences.MAX_AUTOSAVE_INTERVAL_SECONDS + 1).toString()))

        assertNull(historyDepthError(EditorPreferences.DEFAULT_HISTORY_DEPTH.toString()))
        assertNotNull(historyDepthError((EditorPreferences.MIN_HISTORY_DEPTH - 1).toString()))
    }

    @Test
    fun `the message names the offending range`() {
        assertEquals("Must be between 2 and 600.", intRangeError("1", 2, 600))
        assertEquals("Enter a whole number.", intRangeError("x", 2, 600))
    }
}

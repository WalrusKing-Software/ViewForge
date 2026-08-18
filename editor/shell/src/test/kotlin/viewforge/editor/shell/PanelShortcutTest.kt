package viewforge.editor.shell

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The panel show/hide shortcuts (S1, #208) are thin composition glue over [PreferencesController], so what
 * needs pinning is the pure, collision-sensitive part: [panelForShortcut]. It must map Ctrl/Cmd+1..4 to the
 * four panels and reject everything else — an unmodified digit, a Shift/Alt chord, an unrelated key — so the
 * bindings never fire by accident or clash with a future combination.
 */
class PanelShortcutTest {
    @Test
    fun `command plus 1 through 4 map to the four panels in order`() {
        assertEquals(EditorPanel.PALETTE, panelForShortcut(Key.One, cmd = true, shift = false, alt = false))
        assertEquals(EditorPanel.TREE, panelForShortcut(Key.Two, cmd = true, shift = false, alt = false))
        assertEquals(EditorPanel.INSPECTOR, panelForShortcut(Key.Three, cmd = true, shift = false, alt = false))
        assertEquals(EditorPanel.CODE_PREVIEW, panelForShortcut(Key.Four, cmd = true, shift = false, alt = false))
    }

    @Test
    fun `a digit without the command modifier is not a panel shortcut`() {
        assertNull(panelForShortcut(Key.One, cmd = false, shift = false, alt = false))
    }

    @Test
    fun `a Shift or Alt chord is rejected so those combinations stay free`() {
        assertNull(panelForShortcut(Key.One, cmd = true, shift = true, alt = false))
        assertNull(panelForShortcut(Key.Two, cmd = true, shift = false, alt = true))
    }

    @Test
    fun `keys outside 1 through 4 are ignored`() {
        // Ctrl+0 (reset zoom) and Ctrl+9 (fit) belong to other handlers and must not toggle a panel.
        assertNull(panelForShortcut(Key.Zero, cmd = true, shift = false, alt = false))
        assertNull(panelForShortcut(Key.Nine, cmd = true, shift = false, alt = false))
        assertNull(panelForShortcut(Key.Five, cmd = true, shift = false, alt = false))
        assertNull(panelForShortcut(Key.P, cmd = true, shift = false, alt = false))
    }
}

package viewforge.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression guard for #361 / PR #367: a canvas click must move focus TO the canvas element —
 * keeping it inside the shell root's key-event subtree — rather than clearing focus to nothing.
 *
 * The bug: PR #300/#304 introduced [LocalFocusManager.clearFocus] in [SelectionOverlay]'s tap
 * and drag handlers to blur a focused inspector text field. [clearFocus] sends Compose focus to
 * null, which strips the shell root [Column] (the owner of every global [onKeyEvent] shortcut)
 * of focus — with nothing left to reclaim it, every shortcut (undo/redo, zoom, panel toggles)
 * went permanently dead after the first canvas click (#361).
 *
 * The fix (PR #367): [SelectionOverlay] now owns a [FocusRequester] wired to the [Canvas] node.
 * On every tap/drag-start it calls [canvasFocus.requestFocus()] instead of [clearFocus]. The
 * canvas, being a descendant of the shell root [Column], stays inside the [onKeyEvent] subtree,
 * so key events from the focused canvas continue to bubble up to the root handler.
 *
 * [SelectionOverlay] is [internal] to :editor:canvas and cannot be composed from :app directly.
 * This test exercises the identical focus wiring pattern — a root [Column] with [onKeyEvent] and
 * a nested focusable box that calls [FocusRequester.requestFocus] on tap — which is sufficient to
 * catch a re-introduction of [clearFocus] or equivalent in that code path.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasKeyShortcutRetentionTest {

    @Test
    fun `canvas click moves focus to canvas, keeping root onKeyEvent alive (#361)`() = runComposeUiTest {
        var keyHandlerFired by mutableStateOf(false)

        setContent {
            val rootFocus = remember { FocusRequester() }
            val canvasFocus = remember { FocusRequester() }

            // Root container: mirrors EditorShell's focusable Column that owns every global
            // shortcut via onKeyEvent. The handler records whether a key event reached it.
            Column(
                Modifier
                    .fillMaxSize()
                    .focusRequester(rootFocus)
                    .focusable()
                    .onKeyEvent {
                        keyHandlerFired = true
                        true
                    },
            ) {
                // Canvas element: mirrors SelectionOverlay's Canvas node. On tap it calls
                // requestFocus (the fix), moving focus to itself rather than clearing it.
                Box(
                    Modifier
                        .size(300.dp, 300.dp)
                        .testTag("canvas")
                        .focusRequester(canvasFocus)
                        .focusable()
                        .pointerInput(Unit) {
                            detectTapGestures { canvasFocus.requestFocus() }
                        },
                )
            }

            LaunchedEffect(Unit) { rootFocus.requestFocus() }
        }

        // Simulate a tap on the canvas. The tap handler calls canvasFocus.requestFocus(),
        // which moves Compose focus from the root to the canvas — still inside the root's
        // key-event subtree. The broken path (clearFocus) would send focus to null instead.
        onNodeWithTag("canvas").performClick()

        // Focus must sit on the canvas node, not be null. A null focus owner (the clearFocus
        // regression) makes assertIsFocused fail and breaks key event routing to the root.
        onNodeWithTag("canvas").assertIsFocused()

        // A key pressed while the canvas is focused must bubble up through the layout tree
        // and reach the root Column's onKeyEvent handler. If focus had been cleared to null
        // the event would have nowhere to bubble from and the handler would never fire.
        onNodeWithTag("canvas").performKeyInput {
            keyDown(Key.Z)
            keyUp(Key.Z)
        }
        assertTrue(
            keyHandlerFired,
            "root onKeyEvent must fire after a canvas click — focus is still in the subtree (#361)",
        )
    }
}

package viewforge.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.Action
import viewforge.model.EventSlots
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.model.Theme
import viewforge.packages.compose.render.ComposeRenderer
import kotlin.test.Test

/**
 * The C13 run-mode **live screen switching** capability (#325, ADR-039): the composition-level integration the
 * renderer's pure `nextScreen` / `applyAction` unit tests can't reach — that a real pointer click on a rendered
 * Button fires its `onClick` handler, whose [Action.Navigate] threads through the interactive dispatch to the
 * host's `onNavigate`, swaps the host's current screen, and recomposes the target screen's tree. Memory flagged
 * this as GUI-only with no harness; this drives the real [ComposeRenderer.RenderInteractiveProject] through a
 * synthesised click ([runComposeUiTest]), the desktop analogue of the render tests in editor/shell.
 *
 * It lives in :app rather than :packages:compose because it renders through Skiko: the codegen-verify CI gate
 * runs `:packages:compose:test` without the headless graphics libraries, keeping that module a pure
 * codegen/compile gate, whereas :app already renders in tests (`FidelityTest`) under the build gate that
 * installs them — the same wiring boundary that lets :app name the Compose package (ARCHITECTURE §3).
 *
 * The two screens mirror `samples/SchemaV6Kitchen.vforge`: a Home with a "Go to Details" Button whose
 * `onClick` navigates to `scr_details`, and a Details showing "Details screen".
 */
@OptIn(ExperimentalTestApi::class)
class RunModeNavigationTest {
    private fun text(id: String, value: String) = Node(
        NodeId(id),
        "compose.material3.Text",
        props = mapOf("text" to PropValue.Literal(JsonPrimitive(value))),
    )

    private val home = Screen(
        id = "scr_home",
        name = "Home",
        root = Node(
            NodeId("n_home_col"),
            "compose.foundation.layout.Column",
            children = listOf(
                text("n_home_title", "Home screen"),
                Node(
                    NodeId("n_home_nav"),
                    "compose.material3.Button",
                    slots = mapOf("content" to listOf(text("n_home_nav_t", "Go to Details"))),
                    handlers = mapOf(EventSlots.ON_CLICK to listOf(Action.Navigate("scr_details"))),
                ),
            ),
        ),
    )

    private val details = Screen(
        id = "scr_details",
        name = "Details",
        root = Node(
            NodeId("n_det_col"),
            "compose.foundation.layout.Column",
            children = listOf(text("n_det_title", "Details screen")),
        ),
    )

    @Test
    fun `clicking a Navigate button in run mode switches the live preview to the target screen (#325)`() =
        runComposeUiTest {
            setContent {
                ComposeRenderer.RenderInteractiveProject(
                    screens = listOf(home, details),
                    startScreenId = "scr_home",
                    theme = Theme(),
                    dark = false,
                )
            }

            // Starts on Home: the nav button is shown and Details is not yet composed.
            onNodeWithText("Go to Details").assertIsDisplayed()
            onNodeWithText("Details screen").assertDoesNotExist()

            // The click fires onClick -> dispatch -> onNavigate -> nextScreen -> host swaps the drawn screen.
            onNodeWithText("Go to Details").performClick()

            // Now on Details: the target screen's content is live and Home's nav button is gone.
            onNodeWithText("Details screen").assertIsDisplayed()
            onNodeWithText("Go to Details").assertDoesNotExist()
        }

    @Test
    fun `a click whose Navigate targets an unknown screen is a no-op, staying put (PF-6, #325)`() = runComposeUiTest {
        val danglingHome = home.copy(
            root = Node(
                NodeId("n_home_col"),
                "compose.foundation.layout.Column",
                children = listOf(
                    text("n_home_title", "Home screen"),
                    Node(
                        NodeId("n_home_nav"),
                        "compose.material3.Button",
                        slots = mapOf("content" to listOf(text("n_home_nav_t", "Go nowhere"))),
                        handlers = mapOf(EventSlots.ON_CLICK to listOf(Action.Navigate("scr_ghost"))),
                    ),
                ),
            ),
        )
        setContent {
            ComposeRenderer.RenderInteractiveProject(
                screens = listOf(danglingHome, details),
                startScreenId = "scr_home",
                theme = Theme(),
                dark = false,
            )
        }

        // The target isn't a known screen: the host holds position rather than crashing or blanking.
        onNodeWithText("Go nowhere").performClick()
        onNodeWithText("Home screen").assertIsDisplayed()
        onNodeWithText("Details screen").assertDoesNotExist()
    }

    @Test
    fun `leaving run mode returns to the edited screen, not the navigated-to one (#325)`() = runComposeUiTest {
        // Mirrors the editor's Canvas decision (slice 2, #333): the multi-screen host runs while
        // interactive; leaving it renders the *edited* screen's root statically. Because the host holds
        // its current screen in local state and never touches the edited-screen id, exiting shows Home.
        var interactive by mutableStateOf(true)
        setContent {
            if (interactive) {
                ComposeRenderer.RenderInteractiveProject(
                    screens = listOf(home, details),
                    startScreenId = "scr_home",
                    theme = Theme(),
                    dark = false,
                )
            } else {
                ComposeRenderer.RenderScreen(
                    root = home.root,
                    theme = Theme(),
                    dark = false,
                    state = home.state,
                    interactive = false,
                )
            }
        }

        // Navigate away in run mode.
        onNodeWithText("Go to Details").performClick()
        onNodeWithText("Details screen").assertIsDisplayed()

        // Exit run mode: back on the edited screen (Home), never stuck on Details.
        interactive = false
        waitForIdle()
        onNodeWithText("Home screen").assertIsDisplayed()
        onNodeWithText("Details screen").assertDoesNotExist()
    }
}

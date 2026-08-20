package viewforge.project

import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lossless round-trip and the committed sample fixture (PROJECT_PLAN §8 M1 exit criteria). */
class RoundTripTest {
    @Test
    fun `model round-trips through encode then decode`() {
        val project = Fixtures.demoProject()
        assertEquals(project, ProjectCodec.decode(ProjectCodec.encode(project)))
    }

    @Test
    fun `the committed Demo_vforge fixture loads and equals the in-code model`() {
        val samplesDir = System.getProperty("viewforge.samplesDir")
            ?: error("viewforge.samplesDir system property not set by the build")
        val result = ProjectStore.load(Paths.get(samplesDir, "Demo.vforge"))
        assertTrue(result is LoadResult.Success, "expected Success but got $result")
        assertEquals(Fixtures.demoProject(), result.project)
    }

    @Test
    fun `the committed Gallery_vforge sample loads and round-trips losslessly (M9, exit #4)`() {
        // The Phase-1 "something real" screen — nested layout, images, a scrollable list, theme and
        // resource references — must survive save → load unchanged (PROJECT_PLAN §8 exit criterion #4).
        val samplesDir = System.getProperty("viewforge.samplesDir")
            ?: error("viewforge.samplesDir system property not set by the build")
        val result = ProjectStore.load(Paths.get(samplesDir, "Gallery.vforge"))
        assertTrue(result is LoadResult.Success, "expected Success but got $result")
        val project = result.project
        // Encode → decode is the identity, so no field is dropped or reordered on the way through.
        assertEquals(project, ProjectCodec.decode(ProjectCodec.encode(project)))
    }

    @Test
    fun `all five PropValue kinds survive a round-trip and carry the kind discriminator`() {
        val project = Fixtures.demoProject()
        val json = ProjectCodec.encode(project)
        assertContains(json, "\"kind\": \"literal\"")
        assertContains(json, "\"kind\": \"theme\"")
        assertContains(json, "\"kind\": \"expression\"")
        assertEquals(project, ProjectCodec.decode(json))
    }

    @Test
    fun `schemaVersion is always emitted even though it has a default`() {
        assertContains(ProjectCodec.encode(Fixtures.minimalProject()), "\"schemaVersion\": 7")
    }

    @Test
    fun `a ParamRef survives a round-trip and carries the param kind discriminator`() {
        val node = viewforge.model.Node(
            id = viewforge.model.NodeId("n_param"),
            type = "compose.material3.Text",
            props = mapOf("text" to viewforge.model.PropValue.ParamRef("label")),
        )
        val project = Fixtures.minimalProject().let {
            it.copy(components = listOf(viewforge.model.ComponentDef(id = "cmp_1", name = "Labeled", root = node)))
        }
        val json = ProjectCodec.encode(project)
        assertContains(json, "\"kind\": \"param\"")
        assertContains(json, "\"param\": \"label\"")
        assertEquals(project, ProjectCodec.decode(json))
    }

    @Test
    fun `responsive per-breakpoint overrides survive a round-trip (ADR-030)`() {
        // A node with a base prop and an `expanded`-breakpoint override for it must round-trip losslessly,
        // carrying the breakpoint id and the overriding value (schema v7, #221).
        val node = viewforge.model.Node(
            id = viewforge.model.NodeId("n_resp"),
            type = "compose.material3.Text",
            props = mapOf("text" to viewforge.model.PropValue.Literal(JsonPrimitive("Base"))),
            responsive = mapOf(
                "expanded" to mapOf("text" to viewforge.model.PropValue.Literal(JsonPrimitive("Wide"))),
            ),
        )
        val project = Fixtures.minimalProject().copy(
            screens = listOf(viewforge.model.Screen(id = "s", name = "S", root = node)),
        )
        val json = ProjectCodec.encode(project)
        assertContains(json, "\"responsive\"")
        assertContains(json, "\"expanded\"")
        assertEquals(project, ProjectCodec.decode(json))
    }

    @Test
    fun `a node with no responsive overrides omits the field (encodeDefaults=false)`() {
        // A non-responsive node must serialize exactly as before the v7 bump — the defaulted empty map is omitted.
        assertTrue(!ProjectCodec.encode(Fixtures.demoProject()).contains("\"responsive\""))
    }

    @Test
    fun `a saved project contains no absolute paths, usernames, or machine identifiers (PR-4)`() {
        val json = ProjectCodec.encode(Fixtures.demoProject())
        // Windows drive paths, UNC, and common home-dir roots must never leak into a .vforge file.
        val leaks = listOf("C:\\", "C:/", "\\\\", "/Users/", "/home/", "/root/")
        leaks.forEach { assertTrue(!json.contains(it), "saved project leaked '$it'") }
    }

    @Test
    fun `round-trip is idempotent through the store's save and load`() {
        val project = Fixtures.demoProject()
        val tmp = Files.createTempDirectory("vforge-roundtrip").resolve("Demo.vforge")
        ProjectStore.save(project, tmp)
        val result = ProjectStore.load(tmp)
        assertTrue(result is LoadResult.Success)
        assertEquals(project, result.project)
    }

    @Test
    fun `the committed schema-7 Dashboard_vforge fixture loads and equals the in-code model`() {
        // Dashboard.vforge is the ADR-034 read-only-state fixture: screen state (scalar + list-of-record),
        // a StateBinding prop, and a vforge.repeat template. It is current-schema, so it loads without
        // migration and must decode to exactly the in-code fixture (DATA_MODEL rule 3).
        val result = ProjectStore.load(Paths.get(samplesDir(), "Dashboard.vforge"))
        assertTrue(result is LoadResult.Success, "expected Success but got $result")
        assertEquals(Fixtures.stateProject(), result.project)
    }

    @Test
    fun `interactive handlers and the closed Action set survive a round-trip (ADR-035)`() {
        // A screen with writable state and a Button whose onClick handler holds a closed Action list
        // (Adjust + Toggle) must round-trip losslessly and carry the `kind` discriminators for each action.
        val project = Fixtures.interactiveProject()
        val json = ProjectCodec.encode(project)
        assertContains(json, "\"kind\": \"adjust\"") // Action.Adjust
        assertContains(json, "\"kind\": \"toggle\"") // Action.Toggle
        assertContains(json, "\"onClick\"") // the event-slot key
        assertEquals(project, ProjectCodec.decode(json))

        // The fixture is meaningful: the button carries an ordered onClick action list against writable state.
        val decoded = ProjectCodec.decode(json)
        val button = decoded.screens.single().root.children.single { it.type == "compose.material3.Button" }
        assertEquals(2, button.handlers.getValue("onClick").size)
        assertEquals(listOf("count", "expanded"), decoded.screens.single().state.map { it.name })
    }

    @Test
    fun `a node with no handlers omits the handlers field from the serialized form (encodeDefaults=false)`() {
        // A non-interactive node must serialize exactly as before the v6 bump — the defaulted empty `handlers`
        // map is omitted, so existing projects and goldens are byte-identical.
        assertTrue(!ProjectCodec.encode(Fixtures.demoProject()).contains("\"handlers\""))
    }

    @Test
    fun `component-local state survives a round-trip and resolves against the component (ADR-034 Amendment)`() {
        // A ComponentDef carrying its own screen-style state: a scalar and a list-of-record field, a scalar
        // StateBinding, and a vforge.repeat over the component's own list. It must round-trip losslessly and
        // stay distinct from any screen's state (component-local state, schema v5).
        val project = Fixtures.componentStateProject()
        val json = ProjectCodec.encode(project)
        assertContains(json, "\"kind\": \"binding\"") // PropValue.StateBinding inside the component
        assertContains(json, "\"kind\": \"listOfRecord\"") // the component's list-of-record field
        assertEquals(project, ProjectCodec.decode(json))

        // The state lives on the component, not on any screen.
        val component = ProjectCodec.decode(json).components.single()
        assertEquals(listOf("heading", "rows"), component.state.map { it.name })
        assertTrue(project.screens.all { it.state.isEmpty() }, "component state must not leak onto a screen")
    }

    @Test
    fun `an empty component state is omitted from the serialized form (encodeDefaults=false)`() {
        // A stateless component must serialize exactly as before the v5 bump — the defaulted empty `state`
        // list is omitted, so existing projects and goldens are byte-identical.
        val json = ProjectCodec.encode(
            Fixtures.minimalProject().copy(
                components = listOf(
                    viewforge.model.ComponentDef(
                        id = "cmp_bare",
                        name = "Bare",
                        root = viewforge.model.Node(viewforge.model.NodeId("n_bare"), "compose.foundation.layout.Box"),
                    ),
                ),
            ),
        )
        assertTrue(!json.contains("\"state\""), "an empty component state must not be serialized")
    }

    @Test
    fun `the in-code state fixture stays byte-identical to the committed Dashboard_vforge`() {
        // As Gallery is kept in lockstep with the app's sample, the state fixture and its committed
        // serialization must never drift — the encoder writes the file, so they are byte-identical.
        val onDisk = Files.readString(Paths.get(samplesDir(), "Dashboard.vforge"))
        assertEquals(ProjectCodec.encode(Fixtures.stateProject()), onDisk.replace("\r\n", "\n"))
    }

    @Test
    fun `screen state, a StateBinding, and a vforge_repeat all survive a round-trip`() {
        val json = ProjectCodec.encode(Fixtures.stateProject())
        assertContains(json, "\"kind\": \"binding\"") // PropValue.StateBinding
        assertContains(json, "\"kind\": \"listOfRecord\"") // StateType.ListOfRecord
        assertContains(json, "\"type\": \"vforge.repeat\"") // Repeater node
        val decoded = ProjectCodec.decode(json)
        assertEquals(Fixtures.stateProject(), decoded)

        // The fixture is meaningful: the screen declares state and a repeat binds a list source.
        val screen = decoded.screens.single()
        assertEquals(listOf("title", "online", "members"), screen.state.map { it.name })
        val repeat = screen.root.children.single { it.type == viewforge.model.Repeater.TYPE }
        assertEquals("members", viewforge.model.Repeater.sourceOf(repeat))
    }
}

private fun samplesDir(): String = System.getProperty("viewforge.samplesDir")
    ?: error("viewforge.samplesDir system property not set by the build")

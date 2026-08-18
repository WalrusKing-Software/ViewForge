package viewforge.editor.state

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.SampleValue
import viewforge.model.Screen
import viewforge.model.StateType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Declaring and editing read-only screen state (ADR-034) through [EditorState]: the mutators run undoable
 * commands against the active screen, and [EditorState.activeScreenStateForRender] withholds screen state
 * while a component is open for in-place editing. UI gestures are out of scope, as with the other
 * [EditorState] tests.
 */
class ScreenStateEditTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.foundation.layout.Column", "Column", "Layout"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = true

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
            screens = listOf(Screen("s1", "Home", Node(NodeId("root"), "compose.foundation.layout.Column"))),
            components = listOf(
                viewforge.model.ComponentDef(
                    id = "c1",
                    name = "Card",
                    root = Node(NodeId("c-root"), "compose.foundation.layout.Box"),
                ),
            ),
        ),
        FakeCatalog(),
    )

    @Test
    fun `addScalarStateField declares a scalar field and is undoable`() {
        val s = state()
        s.addScalarStateField()
        val field = s.activeScreen!!.state.single()
        assertTrue(field.type is StateType.Scalar)
        assertTrue(field.sample is SampleValue.Scalar)
        s.undo()
        assertEquals(emptyList(), s.activeScreen!!.state)
    }

    @Test
    fun `addListStateField declares a list-of-record field seeded with one row`() {
        val s = state()
        s.addListStateField()
        val field = s.activeScreen!!.state.single()
        assertTrue(field.type is StateType.ListOfRecord)
        assertEquals(1, (field.sample as SampleValue.Rows).rows.size)
        assertEquals(listOf(field), s.listStateFields)
    }

    @Test
    fun `added fields get unique names`() {
        val s = state()
        s.addScalarStateField()
        s.addScalarStateField()
        assertEquals(setOf("field", "field2"), s.activeScreen!!.state.map { it.name }.toSet())
    }

    @Test
    fun `updateStateField replaces the entry in place`() {
        val s = state()
        s.addScalarStateField()
        val renamed = s.activeScreen!!.state[0].copy(
            name = "greeting",
            sample = SampleValue.Scalar(JsonPrimitive("hello")),
        )
        s.updateStateField(0, renamed)
        assertEquals("greeting", s.activeScreen!!.state[0].name)
    }

    @Test
    fun `removeStateField drops the named field and is undoable`() {
        val s = state()
        s.addScalarStateField()
        val name = s.activeScreen!!.state[0].name
        s.removeStateField(name)
        assertEquals(emptyList(), s.activeScreen!!.state)
        s.undo()
        assertEquals(1, s.activeScreen!!.state.size)
    }

    @Test
    fun `activeScreenStateForRender is the screen's state when a screen is the edit surface`() {
        val s = state()
        s.addScalarStateField()
        assertEquals(s.activeScreen!!.state, s.activeScreenStateForRender)
    }

    @Test
    fun `activeScreenStateForRender is empty while a component is open for in-place editing`() {
        val s = state()
        s.addScalarStateField()
        s.openComponent("c1")
        assertEquals(emptyList(), s.activeScreenStateForRender)
        assertEquals(emptyList(), s.bindablePaths(Node(NodeId("c-root"), "compose.foundation.layout.Box")))
    }
}

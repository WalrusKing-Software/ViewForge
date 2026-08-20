package viewforge.editor.state

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropType
import viewforge.model.RecordField
import viewforge.model.Repeater
import viewforge.model.SampleValue
import viewforge.model.ScalarType
import viewforge.model.StateField
import viewforge.model.StateType
import viewforge.model.scalarRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure "what can this node bind to" resolution (ADR-034): a node sees the screen's scalar fields, and
 * inside a `vforge.repeat` template also the current record as `item.<field>`. No composition needed.
 */
class BindablePathsTest {
    private val online =
        StateField("isOnline", StateType.Scalar(ScalarType.BOOL), SampleValue.Scalar(JsonPrimitive(true)))
    private val title =
        StateField("title", StateType.Scalar(ScalarType.STRING), SampleValue.Scalar(JsonPrimitive("Hi")))
    private val users = StateField(
        "users",
        StateType.ListOfRecord(listOf(RecordField("name", ScalarType.STRING), RecordField("age", ScalarType.INT))),
        scalarRows(listOf(mapOf("name" to JsonPrimitive("Ada"), "age" to JsonPrimitive(36)))),
    )
    private val screenState = listOf(online, title, users)

    private val leaf = Node(NodeId("leaf"), "compose.material3.Text")

    @Test
    fun `a node outside any repeat sees only the screen's scalar fields`() {
        val root = Node(NodeId("root"), "compose.foundation.layout.Column", children = listOf(leaf))
        val paths = bindablePaths(root, leaf, screenState).map { it.path }
        assertEquals(listOf("isOnline", "title"), paths) // the list field 'users' is not a scalar
    }

    @Test
    fun `a node inside a repeat also sees item dot field for the source record`() {
        val repeat = Repeater.node("users", template = listOf(leaf))
        val root = Node(NodeId("root"), "compose.foundation.layout.Column", children = listOf(repeat))
        val choices = bindablePaths(root, leaf, screenState)
        assertEquals(listOf("isOnline", "title", "item.name", "item.age"), choices.map { it.path })
        // The item scalars carry the record field's declared type.
        assertEquals(ScalarType.INT, choices.first { it.path == "item.age" }.scalar)
    }

    @Test
    fun `a repeat whose source does not resolve contributes no item paths`() {
        val repeat = Repeater.node("nope", template = listOf(leaf))
        val root = Node(NodeId("root"), "compose.foundation.layout.Column", children = listOf(repeat))
        assertEquals(listOf("isOnline", "title"), bindablePaths(root, leaf, screenState).map { it.path })
    }

    @Test
    fun `a null root yields only the screen scalars`() {
        assertEquals(listOf("isOnline", "title"), bindablePaths(null, leaf, screenState).map { it.path })
    }

    @Test
    fun `listSourceChoices offers top-level lists plus item dot listField inside an enclosing repeat (#255)`() {
        // sections: List<{ title: String, rows: List<{ label: String }> }>
        val sections = StateField(
            "sections",
            StateType.ListOfRecord(
                listOf(
                    RecordField("title", ScalarType.STRING),
                    RecordField("rows", StateType.ListOfRecord(listOf(RecordField("label", ScalarType.STRING)))),
                ),
            ),
            SampleValue.Rows(emptyList()),
        )
        val state = listOf(sections)
        val innerRepeat = Repeater.node("nope", id = NodeId("innerRep"), template = listOf(leaf))
        val outerRepeat = Repeater.node("sections", id = NodeId("outerRep"), template = listOf(innerRepeat))
        val root = Node(NodeId("root"), "compose.foundation.layout.Column", children = listOf(outerRepeat))

        // Inside the `sections` repeat, the inner repeat may bind the top-level list or the nested `item.rows`.
        assertEquals(listOf("sections", "item.rows"), listSourceChoices(root, innerRepeat, state))
        // The outer repeat sits in no enclosing repeat, so only the top-level list is offered.
        assertEquals(listOf("sections"), listSourceChoices(root, outerRepeat, state))
    }

    @Test
    fun `isBindableProp accepts value-like scalars and rejects the rest`() {
        assertTrue(isBindableProp(PropType.String))
        assertTrue(isBindableProp(PropType.Dp))
        assertFalse(isBindableProp(PropType.Color))
        assertFalse(isBindableProp(PropType.Enum))
    }

    @Test
    fun `acceptsScalar is forgiving on numbers and strict on bool`() {
        assertTrue(acceptsScalar(PropType.Dp, ScalarType.INT))
        assertTrue(acceptsScalar(PropType.Float, ScalarType.INT))
        // String takes a number too (#298): codegen coerces a numeric binding with .toString().
        assertTrue(acceptsScalar(PropType.String, ScalarType.STRING))
        assertTrue(acceptsScalar(PropType.String, ScalarType.INT))
        assertTrue(acceptsScalar(PropType.String, ScalarType.FLOAT))
        assertTrue(acceptsScalar(PropType.Bool, ScalarType.BOOL))
        assertFalse(acceptsScalar(PropType.Bool, ScalarType.STRING))
        assertFalse(acceptsScalar(PropType.Bool, ScalarType.INT))
    }
}

package viewforge.packages.compose.codegen

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.Action
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropValue
import viewforge.model.RecordField
import viewforge.model.Repeater
import viewforge.model.ScalarType
import viewforge.model.Screen
import viewforge.model.StateField
import viewforge.model.StateType
import viewforge.model.Theme
import viewforge.model.scalarRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The pure, non-golden corners of state codegen (ADR-034, #21): the generated element-type name, the
 * member-access binding path, and the loud failure a malformed repeat produces. The end-to-end shape is
 * pinned by the `StateBinding` golden + the compile gate; these lock the units it doesn't isolate.
 */
class StateEmitterTest {
    @Test
    fun `a list field's element type name is singularised and capitalised`() {
        assertEquals("Member", StateEmitter.recordTypeName("members"))
        assertEquals("Person", StateEmitter.recordTypeName("person")) // no trailing s → just capitalised
        assertEquals("TeamMember", StateEmitter.recordTypeName("teamMembers")) // camelCase preserved
    }

    @Test
    fun `a binding path emits as dotted member access, never a string literal`() {
        assertEquals("title", CodegenValues.bindingPath("title").toString())
        assertEquals("item.name", CodegenValues.bindingPath("item.name").toString())
    }

    @Test
    fun `recordTypes emits a data class for a nested list field, typed List of its element (#255)`() {
        val state = listOf(
            StateField(
                "departments",
                StateType.ListOfRecord(
                    listOf(
                        RecordField("name", ScalarType.STRING),
                        RecordField(
                            "teams",
                            StateType.ListOfRecord(listOf(RecordField("label", ScalarType.STRING))),
                        ),
                    ),
                ),
                scalarRows(emptyList()),
            ),
        )
        val types = StateEmitter.recordTypes(state)
        // Both the outer and the nested element type are emitted, outer first (depth-first collection).
        assertEquals(listOf("Department", "Team"), types.map { it.name })
        // A bare TypeSpec prints fully-qualified names; the FileSpec resolves this to `List<Team>` on emit.
        val department = types.first { it.name == "Department" }.toString()
        assertTrue(
            "teams: kotlin.collections.List<Team>" in department,
            "nested field must be typed as a List of the element type, got:\n$department",
        )
    }

    // ---- Interactive handlers (ADR-035, #277): action lowering the golden doesn't isolate ----

    private val scalarState = listOf(
        StateField("label", StateType.Scalar(ScalarType.STRING), scalar("hi")),
        StateField("count", StateType.Scalar(ScalarType.INT), scalar(0)),
        StateField("ratio", StateType.Scalar(ScalarType.FLOAT), scalar(0)),
        StateField("flag", StateType.Scalar(ScalarType.BOOL), scalar(false)),
    )

    private fun scalar(v: Any) = viewforge.model.SampleValue.Scalar(prim(v))

    private fun prim(v: Any) = when (v) {
        is String -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Double -> JsonPrimitive(v)
        else -> error("unsupported")
    }

    private fun lower(action: Action, state: List<StateField> = scalarState) =
        StateEmitter.handlerBody(listOf(action), state).toString().trim()

    @Test
    fun `SetState lowers to a typed assignment, quoting strings and leaving numbers bare`() {
        assertEquals("count = 5", lower(Action.SetState("count", PropValue.Literal(prim(5)))))
        assertEquals("label = \"done\"", lower(Action.SetState("label", PropValue.Literal(prim("done")))))
    }

    @Test
    fun `Toggle lowers to f = !f`() {
        assertEquals("flag = !flag", lower(Action.Toggle("flag")))
    }

    @Test
    fun `Adjust lowers to f += delta, typed by the target scalar (Float gets its f suffix)`() {
        assertEquals("count += 1", lower(Action.Adjust("count", PropValue.Literal(prim(1)))))
        assertEquals("ratio += 0.5f", lower(Action.Adjust("ratio", PropValue.Literal(prim(0.5)))))
    }

    @Test
    fun `an action value that is a read binding emits member access, not a literal`() {
        assertEquals("count = other", lower(Action.SetState("count", PropValue.StateBinding("other"))))
    }

    @Test
    fun `AppendRow rebuilds the list with a new typed record element`() {
        val state = listOf(
            StateField(
                "rows",
                StateType.ListOfRecord(
                    listOf(RecordField("name", ScalarType.STRING), RecordField("qty", ScalarType.INT)),
                ),
                scalarRows(emptyList()),
            ),
        )
        val append = Action.AppendRow(
            "rows",
            mapOf("name" to PropValue.Literal(prim("Ada")), "qty" to PropValue.Literal(prim(2))),
        )
        assertEquals("rows = rows + Row(name = \"Ada\", qty = 2)", lower(append, state))
    }

    @Test
    fun `RemoveRow rebuilds the list without the indexed row`() {
        val state = listOf(
            StateField(
                "rows",
                StateType.ListOfRecord(listOf(RecordField("name", ScalarType.STRING))),
                scalarRows(emptyList()),
            ),
        )
        assertEquals(
            "rows = rows.filterIndexed { i, _ -> i != 0 }",
            lower(Action.RemoveRow("rows", PropValue.Literal(prim(0))), state),
        )
    }

    @Test
    fun `Navigate lowers to a call on the injected onNavigate callback with the target screen id (#214)`() {
        assertEquals("onNavigate(\"home\")", lower(Action.Navigate("home")))
    }

    @Test
    fun `writableTargets collects every handler target across children and slots, excluding Navigate`() {
        val inner = Node(
            NodeId("b2"),
            "compose.material3.TextButton",
            handlers = mapOf("onClick" to listOf(Action.Toggle("flag"))),
        )
        val outer = Node(
            NodeId("b1"),
            "compose.material3.Button",
            handlers = mapOf(
                "onClick" to listOf(Action.Adjust("count", PropValue.Literal(prim(1))), Action.Navigate("home")),
            ),
            slots = mapOf("content" to listOf(inner)),
        )
        val root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(outer))
        assertEquals(setOf("count", "flag"), StateEmitter.writableTargets(root))
    }

    @Test
    fun `a repeat node with no source binding fails loudly rather than emitting broken code`() {
        val repeat = Node(NodeId("rep"), Repeater.TYPE) // no `source` prop
        val screen =
            Screen(
                id = "s",
                name = "Broken",
                root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(repeat)),
            )
        assertFailsWith<CodegenException> {
            ComposeCodeGenerator().generateScreen(screen, Theme(), sourceName = "P", schemaVersion = 3)
        }
    }
}

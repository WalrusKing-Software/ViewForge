package viewforge.packages.compose.render

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropValue
import viewforge.model.RecordField
import viewforge.model.Repeater
import viewforge.model.SampleValue
import viewforge.model.ScalarType
import viewforge.model.StateField
import viewforge.model.StateType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure half of read-only data binding on the canvas (ADR-034, #21): resolving [PropValue.StateBinding]s
 * against sample data and expanding [Repeater] nodes to their rows, all as a `Node -> Node` transform so it
 * is testable without a composition — the same discipline as [ParameterBindingTest].
 */
class StateBindingTest {
    private fun scalarField(name: String, type: ScalarType, sample: JsonPrimitive) =
        StateField(name, StateType.Scalar(type), SampleValue.Scalar(sample))

    private fun listField(name: String, fields: List<RecordField>, rows: List<Map<String, JsonPrimitive>>) =
        StateField(name, StateType.ListOfRecord(fields), SampleValue.Rows(rows))

    private fun text(id: String, binding: String) =
        Node(NodeId(id), "compose.material3.Text", props = mapOf("text" to PropValue.StateBinding(binding)))

    private fun boundText(node: Node): PropValue? = node.props["text"]

    @Test
    fun `a scalar binding resolves to its sample literal preserving the JSON type`() {
        val root = Node(
            NodeId("col"),
            "compose.foundation.layout.Column",
            children = listOf(text("t", "title"), text("flag", "online")),
        )
        val state = listOf(
            scalarField("title", ScalarType.STRING, JsonPrimitive("Team Dashboard")),
            scalarField("online", ScalarType.BOOL, JsonPrimitive(true)),
        )
        val expanded = expandScreenState(root, state)
        assertEquals(PropValue.Literal(JsonPrimitive("Team Dashboard")), boundText(expanded.children[0]))
        // The boolean sample stays a boolean literal, not stringified, so a boolean-typed prop still reads it.
        assertEquals(PropValue.Literal(JsonPrimitive(true)), boundText(expanded.children[1]))
    }

    @Test
    fun `an unresolved scalar binding becomes a visible marker literal, never empty`() {
        val root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(text("t", "missing")))
        val expanded = expandScreenState(root, state = emptyList())
        assertEquals(PropValue.Literal(JsonPrimitive(unresolvedMarker("missing"))), boundText(expanded.children[0]))
    }

    @Test
    fun `a repeat expands its template once per sample row with the row as the item scope`() {
        val members = listField(
            "members",
            listOf(RecordField("name", ScalarType.STRING)),
            rows = listOf(
                mapOf("name" to JsonPrimitive("Ada")),
                mapOf("name" to JsonPrimitive("Grace")),
            ),
        )
        val repeat = Repeater.node(
            sourcePath = "members",
            id = NodeId("rep"),
            template = listOf(text("row", "item.name")),
        )
        val root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(repeat))

        val expanded = expandScreenState(root, listOf(members))
        // Two rows -> two spliced siblings in the parent's flow, no repeat node left, no wrapper.
        assertEquals(2, expanded.children.size)
        assertTrue(expanded.children.none { it.type == Repeater.TYPE })
        assertEquals(PropValue.Literal(JsonPrimitive("Ada")), boundText(expanded.children[0]))
        assertEquals(PropValue.Literal(JsonPrimitive("Grace")), boundText(expanded.children[1]))
    }

    @Test
    fun `repeated siblings get distinct ids so canvas keying does not collide`() {
        val members = listField(
            "members",
            listOf(RecordField("name", ScalarType.STRING)),
            rows = listOf(mapOf("name" to JsonPrimitive("Ada")), mapOf("name" to JsonPrimitive("Grace"))),
        )
        val repeat = Repeater.node("members", id = NodeId("rep"), template = listOf(text("row", "item.name")))
        val root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(repeat))

        val ids = expandScreenState(root, listOf(members)).children.map { it.id.value }
        assertEquals(ids, ids.distinct(), "expanded row ids must be unique, got $ids")
    }

    @Test
    fun `repeat rows are bounded to the row limit`() {
        val rows = (1..10).map { mapOf("name" to JsonPrimitive("m$it")) }
        val members = listField("members", listOf(RecordField("name", ScalarType.STRING)), rows)
        val repeat = Repeater.node("members", id = NodeId("rep"), template = listOf(text("row", "item.name")))
        val root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(repeat))

        val expanded = expandScreenState(root, listOf(members), rowLimit = 3)
        assertEquals(3, expanded.children.size)
        assertEquals(PropValue.Literal(JsonPrimitive("m1")), boundText(expanded.children[0]))
        assertEquals(PropValue.Literal(JsonPrimitive("m3")), boundText(expanded.children[2]))
    }

    @Test
    fun `a lazyColumn repeat still previews as spliced rows, not a LazyColumn wrapper (layout is codegen-only)`() {
        val members = listField(
            "members",
            listOf(RecordField("name", ScalarType.STRING)),
            rows = listOf(mapOf("name" to JsonPrimitive("Ada")), mapOf("name" to JsonPrimitive("Grace"))),
        )
        // A repeat marked lazyColumn: the canvas stays layout-neutral to avoid an unbounded-height crash, so
        // the rows still splice into the parent's flow exactly as forEach does. Only codegen emits LazyColumn.
        val repeat = Repeater.node("members", id = NodeId("rep"), template = listOf(text("row", "item.name")))
            .let { it.copy(props = it.props + (Repeater.LAYOUT_PROP to lit(Repeater.LAYOUT_LAZY_COLUMN))) }
        val root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(repeat))

        val expanded = expandScreenState(root, listOf(members))
        assertEquals(2, expanded.children.size)
        assertTrue(expanded.children.none { it.type == "compose.foundation.lazy.LazyColumn" }, "no LazyColumn wrapper")
        assertTrue(expanded.children.none { it.type == Repeater.TYPE })
        assertEquals(PropValue.Literal(JsonPrimitive("Ada")), boundText(expanded.children[0]))
    }

    @Test
    fun `a repeat whose source names no list field becomes a loud placeholder node`() {
        val repeat = Repeater.node("nope", id = NodeId("rep"), template = listOf(text("row", "item.name")))
        val root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(repeat))

        val expanded = expandScreenState(root, state = emptyList())
        val child = expanded.children.single()
        assertEquals(PLACEHOLDER_TYPE, child.type)
        assertTrue(
            (child.props[PLACEHOLDER_MESSAGE_PROP] as PropValue.Literal).value.content.contains("nope"),
            "placeholder should name the unbound source, got ${child.props}",
        )
    }

    @Test
    fun `a screen field binding still resolves inside a repeat template alongside the item scope`() {
        val members = listField(
            "members",
            listOf(RecordField("name", ScalarType.STRING)),
            rows = listOf(mapOf("name" to JsonPrimitive("Ada"))),
        )
        val title = scalarField("title", ScalarType.STRING, JsonPrimitive("Members"))
        // A row whose template binds both an item field and a screen-level field.
        val template = Node(
            NodeId("row"),
            "compose.foundation.layout.Row",
            children = listOf(text("name", "item.name"), text("hdr", "title")),
        )
        val repeat = Repeater.node("members", id = NodeId("rep"), template = listOf(template))
        val root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(repeat))

        val row = expandScreenState(root, listOf(members, title)).children.single()
        assertEquals(PropValue.Literal(JsonPrimitive("Ada")), boundText(row.children[0]))
        assertEquals(PropValue.Literal(JsonPrimitive("Members")), boundText(row.children[1]))
    }

    @Test
    fun `a tree with no bindings or repeats keeps its structure and node ids`() {
        val root = Node(
            NodeId("col"),
            "compose.foundation.layout.Column",
            children = listOf(Node(NodeId("t"), "compose.material3.Text", props = mapOf("text" to lit("hi")))),
        )
        val expanded = expandScreenState(root, state = emptyList())
        assertEquals(root, expanded)
    }

    private fun lit(v: String) = PropValue.Literal(JsonPrimitive(v))
}

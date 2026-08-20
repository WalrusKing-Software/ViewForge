package viewforge.packages.compose.render

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.ComponentDef
import viewforge.model.Dropdown
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Parameter
import viewforge.model.PropValue
import viewforge.model.RecordField
import viewforge.model.Repeater
import viewforge.model.SampleValue
import viewforge.model.ScalarType
import viewforge.model.StateField
import viewforge.model.StateType
import viewforge.model.UserComponent
import viewforge.model.scalarRows
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
        StateField(name, StateType.ListOfRecord(fields), scalarRows(rows))

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

    /** `sections: List<{ title: String, rows: List<{ label: String }> }>` — a nested list-of-record (#255). */
    private fun sectionsState(): StateField {
        val sectionRecord = listOf(
            RecordField("title", ScalarType.STRING),
            RecordField("rows", StateType.ListOfRecord(listOf(RecordField("label", ScalarType.STRING)))),
        )
        fun cell(v: String): SampleValue = SampleValue.Scalar(JsonPrimitive(v))
        fun subRow(label: String) = mapOf("label" to cell(label))
        fun section(title: String, vararg labels: String) =
            mapOf("title" to cell(title), "rows" to SampleValue.Rows(labels.map(::subRow)))
        return StateField(
            "sections",
            StateType.ListOfRecord(sectionRecord),
            SampleValue.Rows(listOf(section("A", "x", "y"), section("B", "z"))),
        )
    }

    private fun textContent(node: Node): String = (boundText(node) as PropValue.Literal).value.content

    @Test
    fun `a nested repeat expands over the outer row's sub-list with item shadowing`() {
        val inner = Repeater.node("item.rows", id = NodeId("inner"), template = listOf(text("cell", "item.label")))
        val outer = Repeater.node(
            "sections",
            id = NodeId("outer"),
            template = listOf(text("hdr", "item.title"), inner),
        )
        val root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(outer))

        val expanded = expandScreenState(root, listOf(sectionsState()))
        // Section A → title "A" then rows x,y; Section B → title "B" then row z. Order preserved, no nodes left.
        assertEquals(listOf("A", "x", "y", "B", "z"), expanded.children.map(::textContent))
        assertTrue(expanded.children.none { it.type == Repeater.TYPE }, "no repeat nodes remain after expansion")
        val ids = expanded.children.map { it.id.value }
        assertEquals(ids, ids.distinct(), "nested-expanded ids must stay unique, got $ids")
    }

    @Test
    fun `an inner item shadows the outer — an outer-only field does not resolve inside a nested repeat`() {
        // The inner template binds `item.title`, which only the *outer* section row has; inside the inner repeat
        // `item` is the sub-row (which has no title), so it must not resolve — it shows the loud marker (PF-6).
        val inner = Repeater.node("item.rows", id = NodeId("inner"), template = listOf(text("cell", "item.title")))
        val outer = Repeater.node("sections", id = NodeId("outer"), template = listOf(inner))
        val root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(outer))

        val expanded = expandScreenState(root, listOf(sectionsState()))
        // Three sub-rows total (x, y, z), each an unresolved `item.title` marker — never the outer section title.
        assertEquals(3, expanded.children.size)
        assertTrue(
            expanded.children.all { textContent(it) == unresolvedMarker("item.title") },
            "inner item must shadow the outer; got ${expanded.children.map(::textContent)}",
        )
    }

    @Test
    fun `a nested repeat whose source names no sub-list becomes a loud placeholder`() {
        val inner = Repeater.node("item.missing", id = NodeId("inner"), template = listOf(text("cell", "item.label")))
        val outer = Repeater.node("sections", id = NodeId("outer"), template = listOf(inner))
        val root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(outer))

        val expanded = expandScreenState(root, listOf(sectionsState()))
        // One placeholder per outer section (2), each naming the unresolved nested source.
        assertEquals(2, expanded.children.size)
        assertTrue(
            expanded.children.all {
                it.type == PLACEHOLDER_TYPE
            },
            "each unresolved nested repeat is a placeholder",
        )
        assertTrue(
            expanded.children.all {
                (it.props[PLACEHOLDER_MESSAGE_PROP] as PropValue.Literal).value.content.contains("item.missing")
            },
            "placeholder should name the unbound nested source",
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
    fun `a populated dropdown previews the first sample row's label value, read-only`() {
        val guests = listField(
            "guests",
            listOf(RecordField("name", ScalarType.STRING), RecordField("seat", ScalarType.INT)),
            rows = listOf(
                mapOf("name" to JsonPrimitive("Ada"), "seat" to JsonPrimitive(1)),
                mapOf("name" to JsonPrimitive("Grace"), "seat" to JsonPrimitive(2)),
            ),
        )
        val dropdown = Dropdown.node("guests", "name", id = NodeId("dd"))
        val root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(dropdown))

        val resolved = expandScreenState(root, listOf(guests)).children.single()
        assertEquals(Dropdown.TYPE, resolved.type)
        assertEquals(PropValue.Literal(JsonPrimitive("Ada")), resolved.props[DROPDOWN_SELECTED_PROP])
        // The options binding is left intact (unread by the renderer), never scalar-clobbered into a marker.
        assertEquals(PropValue.StateBinding("guests"), resolved.props[Dropdown.OPTIONS_PROP])
    }

    @Test
    fun `a dropdown with no label field falls back to the record's first field`() {
        val guests = listField(
            "guests",
            listOf(RecordField("name", ScalarType.STRING), RecordField("seat", ScalarType.INT)),
            rows = listOf(mapOf("name" to JsonPrimitive("Ada"), "seat" to JsonPrimitive(1))),
        )
        val dropdown = Dropdown.node("guests", id = NodeId("dd"))
        val root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(dropdown))

        val resolved = expandScreenState(root, listOf(guests)).children.single()
        assertEquals(PropValue.Literal(JsonPrimitive("Ada")), resolved.props[DROPDOWN_SELECTED_PROP])
    }

    @Test
    fun `a dropdown whose options name no list field becomes a loud placeholder node`() {
        val dropdown = Dropdown.node("nope", "name", id = NodeId("dd"))
        val root = Node(NodeId("col"), "compose.foundation.layout.Column", children = listOf(dropdown))

        val child = expandScreenState(root, state = emptyList()).children.single()
        assertEquals(PLACEHOLDER_TYPE, child.type)
        assertTrue(
            (child.props[PLACEHOLDER_MESSAGE_PROP] as PropValue.Literal).value.content.contains("nope"),
            "placeholder should name the unbound options source, got ${child.props}",
        )
    }

    @Test
    fun `a component instance inlines its own component-local state alongside its parameters (ADR-034 Amendment)`() {
        // A component carrying BOTH a parameter and its own state: heading (scalar) + rows (list). Its root
        // shows a ParamRef, binds the component scalar, and repeats the component list. Inlining an instance is
        // exactly bindParameters (ADR-028) then expandScreenState(def.state) — the composition RenderUserComponent
        // performs — proving params and component-local StateBindings coexist and never collide.
        val def = ComponentDef(
            id = "card",
            name = "Card",
            parameters = listOf(Parameter("title", "String")),
            root = Node(
                NodeId("c-col"),
                "compose.foundation.layout.Column",
                children = listOf(
                    Node(NodeId("c-t"), "compose.material3.Text", props = mapOf("text" to PropValue.ParamRef("title"))),
                    text("c-heading", "heading"),
                    Repeater.node("rows", id = NodeId("c-rep"), template = listOf(text("c-row", "item.label"))),
                ),
            ),
            state = listOf(
                scalarField("heading", ScalarType.STRING, JsonPrimitive("Members")),
                listField(
                    "rows",
                    listOf(RecordField("label", ScalarType.STRING)),
                    rows = listOf(mapOf("label" to JsonPrimitive("Ada")), mapOf("label" to JsonPrimitive("Grace"))),
                ),
            ),
        )
        val instance = Node(
            NodeId("inst"),
            UserComponent.TYPE,
            props = mapOf(UserComponent.COMPONENT_ID_PROP to lit("card"), "title" to lit("Team")),
        )

        val inlined = expandScreenState(bindParameters(def, instance), def.state)
        val kids = inlined.children
        // The parameter resolved to the instance's argument.
        assertEquals(PropValue.Literal(JsonPrimitive("Team")), kids[0].props["text"])
        // The component-local scalar binding resolved to the component's own sample — not any screen's.
        assertEquals(PropValue.Literal(JsonPrimitive("Members")), boundText(kids[1]))
        // The repeat expanded over the component's own list: two spliced rows, no repeat node left.
        assertTrue(kids.none { it.type == Repeater.TYPE })
        assertEquals(PropValue.Literal(JsonPrimitive("Ada")), boundText(kids[2]))
        assertEquals(PropValue.Literal(JsonPrimitive("Grace")), boundText(kids[3]))
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

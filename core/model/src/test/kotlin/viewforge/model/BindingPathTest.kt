package viewforge.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Binding-path parsing and resolution (ADR-034, #21): a dotted identifier path navigated by structural lookup
 * over declared screen state and, inside a [Repeater] template, the current record — never evaluated (PF-4).
 * Pure and Compose-free, shared by the renderer (values), the generator (shapes), and the editor (validation).
 */
class BindingPathTest {
    private val progress =
        StateField("progress", StateType.Scalar(ScalarType.FLOAT), SampleValue.Scalar(JsonPrimitive(0.6)))
    private val title =
        StateField("title", StateType.Scalar(ScalarType.STRING), SampleValue.Scalar(JsonPrimitive("Hi")))
    private val items = StateField(
        "items",
        StateType.ListOfRecord(listOf(RecordField("name", ScalarType.STRING), RecordField("qty", ScalarType.INT))),
        SampleValue.Rows(
            listOf(
                mapOf("name" to JsonPrimitive("Apple"), "qty" to JsonPrimitive(3)),
                mapOf("name" to JsonPrimitive("Pear"), "qty" to JsonPrimitive(1)),
            ),
        ),
    )
    private val fields = listOf(progress, title, items)

    @Test
    fun `parseBindingPath splits a dotted path and rejects malformed ones`() {
        assertEquals(listOf("progress"), parseBindingPath("progress"))
        assertEquals(listOf("item", "name"), parseBindingPath("item.name"))
        assertNull(parseBindingPath("")) // empty
        assertNull(parseBindingPath("a..b")) // empty middle segment
        assertNull(parseBindingPath("1bad")) // leading digit
        assertNull(parseBindingPath("has space")) // illegal char
        assertNull(parseBindingPath("trailing.")) // empty trailing segment
    }

    @Test
    fun `isBindingIdentifier accepts identifiers and rejects the rest`() {
        assertTrue(isBindingIdentifier("_x9"))
        assertTrue(!isBindingIdentifier("9x"))
        assertTrue(!isBindingIdentifier(""))
        assertTrue(!isBindingIdentifier("a-b"))
    }

    @Test
    fun `resolveBindingType resolves a scalar screen field`() {
        assertEquals(ScalarType.FLOAT, resolveBindingType("progress", BindingTypeScope(fields)))
        assertEquals(ScalarType.STRING, resolveBindingType("title", BindingTypeScope(fields)))
    }

    @Test
    fun `resolveBindingType resolves an item-scoped record field inside a repeat`() {
        val scope = BindingTypeScope(fields, itemFields = (items.type as StateType.ListOfRecord).fields)
        assertEquals(ScalarType.STRING, resolveBindingType("item.name", scope))
        assertEquals(ScalarType.INT, resolveBindingType("item.qty", scope))
    }

    @Test
    fun `resolveBindingType returns null for unresolved, list, out-of-scope, and wrong-arity paths`() {
        val scope = BindingTypeScope(fields)
        assertNull(resolveBindingType("ghost", scope)) // unknown field
        assertNull(resolveBindingType("items", scope)) // a list is not a scalar binding
        assertNull(resolveBindingType("item.name", scope)) // no item scope here
        assertNull(resolveBindingType("progress.x", scope)) // scalar has no sub-field
    }

    @Test
    fun `resolveSampleScalar returns the design-time value of a scalar field`() {
        assertEquals(JsonPrimitive(0.6), resolveSampleScalar("progress", BindingValueScope(fields)))
        assertNull(resolveSampleScalar("items", BindingValueScope(fields))) // list field is not a scalar sample
        assertNull(resolveSampleScalar("ghost", BindingValueScope(fields)))
    }

    @Test
    fun `resolveSampleScalar reads the current item row inside a repeat`() {
        val row = (items.sample as SampleValue.Rows).rows.first()
        val scope = BindingValueScope(fields, itemRow = row)
        assertEquals(JsonPrimitive("Apple"), resolveSampleScalar("item.name", scope))
        assertEquals(JsonPrimitive(3), resolveSampleScalar("item.qty", scope))
        assertNull(resolveSampleScalar("item.missing", scope))
    }

    @Test
    fun `resolveListSource finds a list-of-record field and rejects the rest`() {
        assertSame(items, resolveListSource("items", fields))
        assertNull(resolveListSource("progress", fields)) // scalar, not a list
        assertNull(resolveListSource("ghost", fields))
        assertNull(resolveListSource("items.name", fields)) // a source is a single segment
    }

    @Test
    fun `Repeater node carries a StateBinding source and reads back`() {
        val template = listOf(Node(NodeId("t"), "compose.material3.Text"))
        val repeat = Repeater.node("items", template)
        assertEquals(Repeater.TYPE, repeat.type)
        assertEquals(PropValue.StateBinding("items"), repeat.props[Repeater.SOURCE_PROP])
        assertEquals(template, repeat.children)
        assertEquals("items", Repeater.sourceOf(repeat))
        assertNull(Repeater.sourceOf(Node(NodeId("x"), "compose.material3.Text"))) // not a repeat
    }
}

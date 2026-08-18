package viewforge.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * Read-only, design-time **screen state** (ADR-034, #21): the named data a screen's props bind to. Each
 * [StateField] declares a [name] (the binding root — a legal Kotlin identifier, GC-3), a structured [type],
 * and a typed [sample] used to preview the UI on the canvas and to seed generated code. State is *data*, not
 * code: samples are typed literals, never expressions, so there is no evaluation path (PF-4). This release is
 * read-only — no mutation or events — and screen-scoped; component-local state and nested lists are deferred.
 */
@Serializable
data class StateField(val name: String, val type: StateType, val sample: SampleValue)

/** The scalar value types a piece of state (or a record field) can hold this release. */
@Serializable
enum class ScalarType { STRING, INT, FLOAT, BOOL }

/** One named, scalar-typed field of a record (a row of a list-of-record state). Names are identifiers (GC-3). */
@Serializable
data class RecordField(val name: String, val scalar: ScalarType)

/**
 * The shape of a [StateField]: a single [Scalar] value, or a [ListOfRecord] — a list whose elements are flat
 * records (named scalar fields). These two cover the issue's examples (a live indicator binds a scalar; a
 * dynamic list / populated dropdown repeats over a list of records). A closed hierarchy (PF-1); serialized
 * with the `kind` discriminator, so no member may declare a `kind` property.
 */
@Serializable
sealed interface StateType {
    @Serializable
    @SerialName("scalar")
    data class Scalar(val scalar: ScalarType) : StateType

    @Serializable
    @SerialName("listOfRecord")
    data class ListOfRecord(val fields: List<RecordField>) : StateType
}

/**
 * The design-time sample backing a [StateField]: a [Scalar] literal, or [Rows] — a list of records, each a
 * map of field name → scalar literal. Sample data is what the canvas renders and what codegen seeds the
 * generated stub with (never a live source; ADR-034). A closed hierarchy tagged with `kind`.
 */
@Serializable
sealed interface SampleValue {
    @Serializable
    @SerialName("scalar")
    data class Scalar(val value: JsonPrimitive) : SampleValue

    @Serializable
    @SerialName("rows")
    data class Rows(val rows: List<Map<String, JsonPrimitive>>) : SampleValue
}

/**
 * The schema contract for a **repeat** node (ADR-034, #21): a node whose subtree is rendered once per element
 * of a list-typed state field. Its [SOURCE_PROP] prop carries a [PropValue.StateBinding] to the list field;
 * its children are the per-item template, in which bindings resolve against an [ITEM_SCOPE] scope (the current
 * record). Modeled like a user-component instance (ADR-024): a reference + template, expanded at render and
 * codegen, edited once — not inlined per copy. These constants are the single source of that contract, shared
 * by the validator, renderer, and generator, mirroring [UserComponent].
 */
object Repeater {
    const val TYPE: String = "vforge.repeat"
    const val SOURCE_PROP: String = "source"

    /** The path root a binding uses inside a repeat template to name the current element (e.g. `item.title`). */
    const val ITEM_SCOPE: String = "item"

    /** A fresh repeat node bound to the list field [sourcePath], holding [template] as its per-item subtree. */
    fun node(sourcePath: String, template: List<Node> = emptyList(), id: NodeId = NodeId.random()): Node = Node(
        id = id,
        type = TYPE,
        props = mapOf(SOURCE_PROP to PropValue.StateBinding(sourcePath)),
        children = template,
    )

    /** The source list path this repeat binds to, or null if [node] is not a repeat / carries no binding. */
    fun sourceOf(node: Node): String? =
        if (node.type == TYPE) (node.props[SOURCE_PROP] as? PropValue.StateBinding)?.path else null
}

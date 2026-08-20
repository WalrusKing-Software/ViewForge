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

/**
 * One named field of a record (a row of a list-of-record state). Its [type] is a full [StateType], so a field
 * is either a scalar or **itself a nested list-of-record** (ADR-034 Amendment, #255): the model is recursive.
 * Names are identifiers (GC-3). The [scalar] secondary constructor keeps the common flat case terse:
 * `RecordField("name", ScalarType.STRING)`.
 */
@Serializable
data class RecordField(val name: String, val type: StateType) {
    constructor(name: String, scalar: ScalarType) : this(name, StateType.Scalar(scalar))
}

/** The scalar type of a flat record field, or null when the field is itself a nested list-of-record. */
val RecordField.scalarOrNull: ScalarType? get() = (type as? StateType.Scalar)?.scalar

/**
 * The shape of a [StateField] (or a [RecordField]): a single [Scalar] value, or a [ListOfRecord] — a list
 * whose elements are records ([RecordField]s). A record field may itself be a [ListOfRecord], so the hierarchy
 * is **recursive** — nested lists (ADR-034 Amendment, #255). These cover the issue's examples (a live indicator
 * binds a scalar; a dynamic list / populated dropdown / nested list repeats over records). A closed hierarchy
 * (PF-1); serialized with the `kind` discriminator, so no member may declare a `kind` property.
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
 * The design-time sample backing a [StateField] (or record field): a [Scalar] literal, or [Rows] — a list of
 * records, each a map of field name → [SampleValue]. A cell is itself a [SampleValue], so a nested-list field's
 * sample is [Rows] within a row — mirroring the recursive [StateType] (ADR-034 Amendment, #255). Sample data is
 * what the canvas renders and what codegen seeds the generated stub with (never a live source; ADR-034). A
 * closed hierarchy tagged with `kind`.
 */
@Serializable
sealed interface SampleValue {
    @Serializable
    @SerialName("scalar")
    data class Scalar(val value: JsonPrimitive) : SampleValue

    @Serializable
    @SerialName("rows")
    data class Rows(val rows: List<Map<String, SampleValue>>) : SampleValue
}

/** The scalar primitive of a sample cell, or null when the cell holds nested [SampleValue.Rows]. */
val SampleValue?.scalarValue: JsonPrimitive? get() = (this as? SampleValue.Scalar)?.value

/** Build [SampleValue.Rows] from flat scalar rows (field name → primitive) — the common, non-nested case. */
fun scalarRows(rows: List<Map<String, JsonPrimitive>>): SampleValue.Rows =
    SampleValue.Rows(rows.map { row -> row.mapValues { (_, v) -> SampleValue.Scalar(v) } })

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

    /**
     * The prop selecting how a repeat lays its rows out (ADR-034 slice 2). Absent ⇒ [LAYOUT_FOR_EACH]: the rows
     * land inline in the parent's flow. [LAYOUT_LAZY_COLUMN] instead renders/emits a scrolling `LazyColumn`.
     * An ordinary [PropValue.Literal] string, so it is an additive prop — **no schema change** (a repeat without
     * it reads as `forEach`, exactly as every pre-slice-2 repeat does).
     */
    const val LAYOUT_PROP: String = "layout"
    const val LAYOUT_FOR_EACH: String = "forEach"
    const val LAYOUT_LAZY_COLUMN: String = "lazyColumn"

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

    /** The layout mode of [node]: its [LAYOUT_PROP] literal, or [LAYOUT_FOR_EACH] when unset/not a repeat. */
    fun layoutOf(node: Node): String =
        (node.props[LAYOUT_PROP] as? PropValue.Literal)?.value?.content ?: LAYOUT_FOR_EACH

    /** Whether [node] renders/emits as a scrolling `LazyColumn` rather than an inline `forEach`. */
    fun isLazyColumn(node: Node): Boolean = layoutOf(node) == LAYOUT_LAZY_COLUMN
}

/**
 * The schema contract for a **populated dropdown** (ADR-034 slice 2, #253): a selection component whose menu is
 * populated, read-only, from a list-of-record state field. Its [OPTIONS_PROP] carries a [PropValue.StateBinding]
 * to that list field (resolved by [resolveListSource], exactly like a repeat's `source`); its [LABEL_PROP] is a
 * literal naming which record field is shown as each option's text. Both are ordinary props, so a dropdown is an
 * **additive, schema-neutral** node — no `.vforge` version bump. Like [Repeater], this is a framework-neutral
 * `vforge.*` concept the compose package realizes (as a Material3 `ExposedDropdownMenuBox`); these constants are
 * the single source of the contract shared by the validator, renderer, generator, and inspector.
 *
 * Read-only this release: the canvas previews the sample rows and codegen seeds a runnable stub — no selection is
 * persisted to state and no event fires (mutation/events remain the separate, consent-gated ADR).
 */
object Dropdown {
    const val TYPE: String = "vforge.dropdown"

    /** The prop binding the menu options to a list-of-record state field (a [PropValue.StateBinding]). */
    const val OPTIONS_PROP: String = "options"

    /** The prop naming which record field of the bound list is shown as each option's text (a literal string). */
    const val LABEL_PROP: String = "optionLabel"

    /** A fresh dropdown bound to the list field [optionsPath], showing record field [labelField] per option. */
    fun node(optionsPath: String = "", labelField: String = "", id: NodeId = NodeId.random()): Node = Node(
        id = id,
        type = TYPE,
        props = buildMap {
            put(OPTIONS_PROP, PropValue.StateBinding(optionsPath))
            if (labelField.isNotBlank()) put(LABEL_PROP, PropValue.Literal(JsonPrimitive(labelField)))
        },
    )

    /** The source list path this dropdown binds its options to, or null if [node] is not a dropdown / unbound. */
    fun optionsOf(node: Node): String? =
        if (node.type == TYPE) (node.props[OPTIONS_PROP] as? PropValue.StateBinding)?.path else null

    /** The record field name shown per option, or null when [node] is not a dropdown or no label field is set. */
    fun labelFieldOf(node: Node): String? = if (node.type ==
        TYPE
    ) {
        (node.props[LABEL_PROP] as? PropValue.Literal)?.value?.content?.takeIf { it.isNotBlank() }
    } else {
        null
    }
}

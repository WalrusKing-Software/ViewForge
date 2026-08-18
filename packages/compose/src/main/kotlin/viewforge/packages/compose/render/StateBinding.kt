package viewforge.packages.compose.render

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.BindingValueScope
import viewforge.model.Dropdown
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.PropValue
import viewforge.model.Repeater
import viewforge.model.SampleValue
import viewforge.model.StateField
import viewforge.model.StateType
import viewforge.model.resolveListSource
import viewforge.model.resolveSampleScalar
import viewforge.model.scalarValue

/**
 * Design-time resolution of read-only screen state for the canvas (ADR-034, #21): a pure `Node -> Node`
 * pre-pass, run once before the interpreter walk, that resolves [PropValue.StateBinding]s against the
 * screen's [StateField.sample] data and expands every [Repeater] node into its per-row content. The
 * result is an ordinary tree with no bindings or repeats left, so the render layer ([RenderNode]) draws
 * it exactly as it draws any other node.
 *
 * This mirrors [bindParameters]: a purely structural substitution over the IR, Compose-free and testable
 * without a composition, so the "what does this bind to" decision has one authoritative, non-`@Composable`
 * answer. It is **never evaluation** (PF-4) — samples are typed literals and paths are navigated by field
 * lookup ([resolveSampleScalar]/[resolveListSource] in core/model), so nothing here parses or runs Kotlin.
 *
 * Two failure modes render loudly rather than silently (PF-6): an unresolved scalar binding becomes a
 * visible [unresolvedMarker] literal (so a bound `Text` shows `⟨path?⟩`, not empty), and a repeat whose
 * `source` doesn't name a list field becomes a [PLACEHOLDER_TYPE] node the dispatch draws as an error box.
 */

/** The design-time cap on repeat rows the canvas draws — sample data is small; this only bounds a runaway. */
internal const val REPEAT_ROW_LIMIT: Int = 50

/** A synthetic, render-only node type (never persisted) standing in for a repeat that can't resolve (PF-6). */
internal const val PLACEHOLDER_TYPE: String = "vforge.render.placeholder"

/** The prop under which a [PLACEHOLDER_TYPE] node carries its user-facing message. */
internal const val PLACEHOLDER_MESSAGE_PROP: String = "message"

/** Render-only prop: the sample selection a resolved [Dropdown] shows (never persisted; set by [expandDropdown]). */
internal const val DROPDOWN_SELECTED_PROP: String = "vforge.dropdown.selected"

/** The visible stand-in a `Text` (or any scalar-bound prop) shows when its binding [path] can't resolve. */
internal fun unresolvedMarker(path: String): String = "⟨$path?⟩" // ⟨path?⟩

/**
 * The screen [root] with every scalar [PropValue.StateBinding] resolved against [state]'s sample data and
 * every [Repeater] expanded to its rows. Entry point for [ComposeRenderer]; [rowLimit] is injectable so a
 * test can pin the bound. Identity-preserving for a tree with no bindings or repeats (node ids are kept, so
 * canvas keying is stable), which is every pre-v3 screen — so non-state screens render exactly as before.
 */
internal fun expandScreenState(root: Node, state: List<StateField>, rowLimit: Int = REPEAT_ROW_LIMIT): Node =
    expandNode(root, BindingValueScope(fields = state), rowLimit)

/** One node: resolve its own binding props/modifier args, then expand its child and slot lists under [scope]. */
private fun expandNode(node: Node, scope: BindingValueScope, rowLimit: Int): Node = if (node.type == Dropdown.TYPE) {
    expandDropdown(node, scope)
} else {
    node.copy(
        props = node.props.resolveBindings(scope),
        modifiers = node.modifiers.map { it.copy(args = it.args.resolveBindings(scope)) },
        children = node.children.expandList(scope, rowLimit),
        slots = node.slots.mapValues { (_, list) -> list.expandList(scope, rowLimit) },
    )
}

/**
 * A populated dropdown resolved for preview (ADR-034 slice 2, #253). Its `options` binds to a list-of-record
 * source — never a scalar — so the generic scalar resolution ([resolveBindings]) must not touch it: instead the
 * canvas previews the dropdown read-only as the **first** sample row's label value, stashed in
 * [DROPDOWN_SELECTED_PROP] for [RenderDropdown] (the original binding prop is left intact but unread). An
 * unbound or unresolvable source becomes a loud placeholder (PF-6), exactly like a repeat. The label field is
 * [Dropdown.labelFieldOf] or, absent, the record's first field. Modifier-arg bindings still resolve as normal.
 */
private fun expandDropdown(node: Node, scope: BindingValueScope): Node {
    val optionsPath = Dropdown.optionsOf(node)
    val listField = optionsPath?.let { resolveListSource(it, scope.fields) }
        ?: return placeholder(node.id, "Unbound options: ${optionsPath?.ifBlank { "?" } ?: "?"}")
    val fields = (listField.type as? StateType.ListOfRecord)?.fields.orEmpty()
    val labelField = Dropdown.labelFieldOf(node) ?: fields.firstOrNull()?.name
    val rows = (listField.sample as? SampleValue.Rows)?.rows.orEmpty()
    val selected = labelField?.let { lf -> rows.firstOrNull()?.get(lf).scalarValue?.content } ?: ""
    return node.copy(
        modifiers = node.modifiers.map { it.copy(args = it.args.resolveBindings(scope)) },
        props = node.props + (DROPDOWN_SELECTED_PROP to PropValue.Literal(JsonPrimitive(selected))),
    )
}

/** Expand a child list, splicing each [Repeater]'s rows inline (a forEach in its parent's flow, not a wrapper). */
private fun List<Node>.expandList(scope: BindingValueScope, rowLimit: Int): List<Node> = flatMap { child ->
    if (child.type == Repeater.TYPE) {
        expandRepeat(child, scope, rowLimit)
    } else {
        listOf(expandNode(child, scope, rowLimit))
    }
}

/**
 * A repeat's rows: the first [rowLimit] rows of the list field its `source` names, each rendering the
 * template ([Repeater] children) with the row exposed as the `item` scope. A source that doesn't resolve to
 * a list field yields a single placeholder (PF-6). Per-row ids are suffixed so the spliced siblings keep
 * distinct, stable keys (duplicate keys among direct siblings would break Compose keying).
 *
 * The canvas preview is **layout-neutral** (ADR-034 slice 2): both `forEach` and `lazyColumn`
 * ([Repeater.layoutOf]) splice their sample rows inline here. A `lazyColumn` repeat is deliberately *not*
 * wrapped in a real `LazyColumn` for preview — a lazy list measured with unbounded height (e.g. inside a
 * `Column`) crashes Compose, and the canvas must never crash. The scrolling `LazyColumn` distinction is a
 * codegen-only concern (`ComponentEmitter.repeater`); against small bounded sample data the previews match.
 */
private fun expandRepeat(node: Node, scope: BindingValueScope, rowLimit: Int): List<Node> {
    val source = Repeater.sourceOf(node)
    val listField = source?.let { resolveListSource(it, scope.fields) }
        ?: return listOf(placeholder(node.id, "Unbound list: ${source ?: "?"}"))
    val rows = (listField.sample as? SampleValue.Rows)?.rows.orEmpty().take(rowLimit)
    return rows.flatMapIndexed { index, row ->
        val itemScope = scope.copy(itemRow = row)
        node.children.map { template -> expandNode(template, itemScope, rowLimit).suffixIds("#$index") }
    }
}

/** Resolve a prop map's [PropValue.StateBinding]s to literals; returns the same instance when there are none. */
private fun Map<String, PropValue>.resolveBindings(scope: BindingValueScope): Map<String, PropValue> =
    if (values.none { it is PropValue.StateBinding }) this else mapValues { (_, v) -> v.resolveBinding(scope) }

/**
 * A [PropValue.StateBinding] becomes a [PropValue.Literal] of its resolved sample scalar — preserving the
 * scalar's JSON type so a boolean-bound prop stays boolean — or a visible [unresolvedMarker] when it can't
 * resolve. Any other value is returned unchanged.
 */
private fun PropValue.resolveBinding(scope: BindingValueScope): PropValue = if (this is PropValue.StateBinding) {
    PropValue.Literal(resolveSampleScalar(path, scope) ?: JsonPrimitive(unresolvedMarker(path)))
} else {
    this
}

/** A render-only placeholder node keyed off the source [from] node, carrying [message] for the error box. */
private fun placeholder(from: NodeId, message: String): Node = Node(
    id = NodeId("${from.value}#unbound"),
    type = PLACEHOLDER_TYPE,
    props = mapOf(PLACEHOLDER_MESSAGE_PROP to PropValue.Literal(JsonPrimitive(message))),
)

/** Append [suffix] to this node's id and every descendant's, making a repeated template copy globally unique. */
private fun Node.suffixIds(suffix: String): Node = copy(
    id = NodeId(id.value + suffix),
    children = children.map { it.suffixIds(suffix) },
    slots = slots.mapValues { (_, list) -> list.map { it.suffixIds(suffix) } },
)

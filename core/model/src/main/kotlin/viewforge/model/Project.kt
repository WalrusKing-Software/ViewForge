@file:OptIn(ExperimentalSerializationApi::class)

package viewforge.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** Records which framework package produced the file (DATA_MODEL §2). */
@Serializable
data class FrameworkRef(val packageId: String, val packageVersion: String)

/**
 * A user-defined composable parameter (DATA_MODEL §4).
 *
 * @property default may be null (parameter has no default).
 */
@Serializable
data class Parameter(val name: String, val type: String, val default: PropValue? = null)

/**
 * The schema conventions for a user-component *instance* node (DATA_MODEL §4): its [Node.type] and the
 * prop key under which it carries the referenced [ComponentDef.id] (a `literal` string). An instance is
 * a thin reference — the definition is resolved at render and codegen time, never inlined into the IR
 * (ADR-024) — so these constants are the single source of that contract, shared by the validator,
 * renderer, and generator alike.
 */
object UserComponent {
    const val TYPE: String = "vforge.userComponent"
    const val COMPONENT_ID_PROP: String = "componentId"

    /**
     * A fresh instance node referencing the component [componentId] — the one place the instance-node
     * shape is built, shared by extraction and palette insertion so the wire form stays single-sourced.
     */
    fun instance(componentId: String, id: NodeId = NodeId.random()): Node = Node(
        id = id,
        type = TYPE,
        props = mapOf(COMPONENT_ID_PROP to PropValue.Literal(JsonPrimitive(componentId))),
    )

    /** The component id an instance [node] references, or null if [node] is not an instance / carries none. */
    fun componentIdOf(node: Node): String? = (node.props[COMPONENT_ID_PROP] as? PropValue.Literal)?.value?.content
}

/**
 * A reusable, user-defined composable (DATA_MODEL §4). Instances reference it via a node of type
 * [UserComponent.TYPE]. A component must never contain itself directly or transitively — cycle
 * detection runs on load, not just on edit (PF-3).
 *
 * [state] is the component's own read-only, design-time data (ADR-034 Amendment, component-local state):
 * the named [StateField]s its internal tree binds to via [PropValue.StateBinding], exactly as a [Screen]
 * does. It is resolved against *this* component's own state — never the enclosing screen's — so an instance
 * is self-contained. Distinct from [parameters], which are supplied by the instance ([PropValue.ParamRef]);
 * inside a component root a prop may reference either, and they never collide (distinct [PropValue] members).
 * Additive and defaulted — a component without data serializes identically to before — though populating it
 * is what claims schema v5 (a v4 build would silently drop it and misrender every component-local binding).
 */
@Serializable
data class ComponentDef(
    val id: String,
    val name: String,
    val parameters: List<Parameter> = emptyList(),
    val root: Node,
    val state: List<StateField> = emptyList(),
)

/**
 * A top-level, exportable UI entry point (DATA_MODEL §3). [name] becomes the generated composable
 * function name and must normalize to a legal Kotlin identifier (validated at edit time / codegen,
 * GC-3).
 *
 * [state] is the screen's read-only, design-time data (ADR-034, #21): the named [StateField]s its props
 * bind to via [PropValue.StateBinding]. Additive and defaulted — a screen without data serializes
 * identically to before — though populating it is what claims schema v3 (a v2 build would silently drop
 * it and misrender every binding).
 */
@Serializable
data class Screen(
    val id: String,
    val name: String,
    val root: Node,
    val previewProfile: String? = null,
    val state: List<StateField> = emptyList(),
)

/**
 * The `.vforge` document root and the single source of truth (DATA_MODEL §2, ARCHITECTURE §1).
 * Immutable.
 *
 * [schemaVersion] is carried explicitly and every load path checks/migrates it (DATA_MODEL §10).
 * No absolute paths, usernames, or machine identifiers live anywhere in here — `.vforge` files get
 * committed to public repos (PR-4).
 */
@Serializable
data class Project(
    // Always emitted even though it has a default: the file must be self-describing for migration
    // (DATA_MODEL §10), and the global format omits defaults for clean diffs.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    val createdAt: String? = null,
    val framework: FrameworkRef,
    val targets: List<String> = listOf("desktop"),
    val theme: Theme = Theme(),
    val screens: List<Screen> = emptyList(),
    val components: List<ComponentDef> = emptyList(),
    val assets: List<Asset> = emptyList(),
)

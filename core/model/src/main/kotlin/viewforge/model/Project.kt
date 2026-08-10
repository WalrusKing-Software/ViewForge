@file:OptIn(ExperimentalSerializationApi::class)

package viewforge.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

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
 * A reusable, user-defined composable (DATA_MODEL §4). Instances reference it via a node of type
 * "vforge.userComponent". A component must never contain itself directly or transitively — cycle
 * detection runs on load, not just on edit (PF-3).
 */
@Serializable
data class ComponentDef(
    val id: String,
    val name: String,
    val parameters: List<Parameter> = emptyList(),
    val root: Node,
)

/**
 * A top-level, exportable UI entry point (DATA_MODEL §3). [name] becomes the generated composable
 * function name and must normalize to a legal Kotlin identifier (validated at edit time / codegen,
 * GC-3).
 */
@Serializable
data class Screen(val id: String, val name: String, val root: Node, val previewProfile: String? = null)

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

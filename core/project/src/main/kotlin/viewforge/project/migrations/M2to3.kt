package viewforge.project.migrations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import viewforge.project.Migration

/**
 * Schema v2 -> v3 (ADR-034, #21): introduces read-only screen state — [viewforge.model.Screen.state]
 * ([viewforge.model.StateField]s) and the `vforge.repeat` node ([viewforge.model.Repeater]), bound to
 * via [viewforge.model.PropValue.StateBinding]. The change is purely data-additive: a v2 document
 * contains no `state` and no repeat nodes, so it is already a structurally valid v3 document. The only
 * thing to do is stamp the version so the decoded [viewforge.model.Project] reports v3 and the file is
 * not re-migrated on every load.
 *
 * The bump exists because populating state is forward-incompatible: a v2-only build (`ignoreUnknownKeys`)
 * would silently drop `state` and cannot resolve a `{"kind":"binding"}` prop, so a v3 file must be
 * marked as such and refused cleanly by older builds (ProjectStore NEWER_SCHEMA gate) rather than
 * loaded and misrendered. Mirrors [M1to2].
 */
object M2to3 : Migration {
    override val fromVersion: Int = 2
    override val toVersion: Int = 3

    override fun migrate(document: JsonObject): JsonObject =
        JsonObject(document + ("schemaVersion" to JsonPrimitive(toVersion)))
}

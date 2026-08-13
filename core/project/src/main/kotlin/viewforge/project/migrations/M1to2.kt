package viewforge.project.migrations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import viewforge.project.Migration

/**
 * Schema v1 -> v2 (ADR-028): introduces [viewforge.model.PropValue.ParamRef] for component
 * parameters. The change is purely data-additive — a v1 document contains no `param` prop values, so
 * it is already a structurally valid v2 document. The only thing to do is stamp the version so the
 * decoded [viewforge.model.Project] reports v2 and the file is not re-migrated on every load.
 *
 * The bump exists because [viewforge.model.PropValue] is a *closed* sealed hierarchy (PF-1): a
 * v1-only build cannot deserialize a `{"kind":"param"}` value, so a v2 file must be marked as such
 * and refused cleanly by older builds (ProjectStore NEWER_SCHEMA gate) rather than failing to parse.
 */
object M1to2 : Migration {
    override val fromVersion: Int = 1
    override val toVersion: Int = 2

    override fun migrate(document: JsonObject): JsonObject =
        JsonObject(document + ("schemaVersion" to JsonPrimitive(toVersion)))
}

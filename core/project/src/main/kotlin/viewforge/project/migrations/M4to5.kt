package viewforge.project.migrations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import viewforge.project.Migration

/**
 * Schema v4 -> v5 (ADR-034 Amendment, component-local state): a [viewforge.model.ComponentDef] gains its own
 * read-only `state` ([viewforge.model.StateField]s), resolved against itself like a [viewforge.model.Screen]'s.
 * The change is purely data-additive: a v4 document has no component `state`, so it is already a structurally
 * valid v5 document. The only thing to do is stamp the version so the decoded project reports v5 and the file
 * is not re-migrated on every load.
 *
 * The bump exists because populating component state is forward-incompatible: a v4-only build (`ignoreUnknownKeys`)
 * would silently drop a component's `state` and cannot resolve its `{"kind":"binding"}` props, so a v5 file must
 * be marked as such and refused cleanly by older builds (ProjectStore NEWER_SCHEMA gate) rather than loaded and
 * misrendered. A pure version stamp, exactly like [M1to2] and [M2to3] (contrast [M3to4], which transforms).
 */
object M4to5 : Migration {
    override val fromVersion: Int = 4
    override val toVersion: Int = 5

    override fun migrate(document: JsonObject): JsonObject =
        JsonObject(document + ("schemaVersion" to JsonPrimitive(toVersion)))
}

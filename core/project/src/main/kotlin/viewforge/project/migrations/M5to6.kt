package viewforge.project.migrations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import viewforge.project.Migration

/**
 * Schema v5 -> v6 (ADR-035, #277): interactive state & events. A [viewforge.model.Node] gains
 * [viewforge.model.Node.handlers] — event slots holding a `List<Action>` from the closed
 * [viewforge.model.Action] set — and declared state fields become writable action targets. The change is purely
 * data-additive: a v5 document has no `handlers`, so it is already a structurally valid v6 document. The only
 * thing to do is stamp the version so the decoded project reports v6 and the file is not re-migrated on load.
 *
 * The bump exists because populating handlers is forward-incompatible: a v5-only build (`ignoreUnknownKeys`)
 * would silently drop a node's `handlers` and cannot resolve its `{"kind":"setState"}` actions, so a v6 file must
 * be marked as such and refused cleanly by older builds (ProjectStore NEWER_SCHEMA gate) rather than loaded and
 * rendered dead. A pure version stamp, exactly like [M1to2], [M2to3], and [M4to5] (contrast [M3to4], which
 * transforms). This claims the slot ADR-030 responsive had reserved, so responsive slides to v7/M6to7.
 */
object M5to6 : Migration {
    override val fromVersion: Int = 5
    override val toVersion: Int = 6

    override fun migrate(document: JsonObject): JsonObject =
        JsonObject(document + ("schemaVersion" to JsonPrimitive(toVersion)))
}

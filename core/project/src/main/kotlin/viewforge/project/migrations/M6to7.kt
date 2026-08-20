package viewforge.project.migrations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import viewforge.project.Migration

/**
 * Schema v6 -> v7 (ADR-030, #221): responsive per-breakpoint overrides. A [viewforge.model.Node] gains
 * [viewforge.model.Node.responsive] — a breakpoint-id → (prop-name → override) map layered over the base
 * props at render/codegen. The change is purely data-additive: a v6 document has no `responsive`, so it is
 * already a structurally valid v7 document. The only thing to do is stamp the version so the decoded project
 * reports v7 and the file is not re-migrated on load.
 *
 * The bump exists because populating overrides is forward-incompatible: a v6-only build (`ignoreUnknownKeys`)
 * would silently drop a node's `responsive` and render/emit only base props (a fidelity loss), so a v7 file
 * must be marked as such and refused cleanly by older builds (ProjectStore NEWER_SCHEMA gate) rather than
 * loaded and rendered wrong. A pure version stamp, exactly like [M1to2], [M2to3], [M4to5], and [M5to6]
 * (contrast [M3to4], which transforms).
 */
object M6to7 : Migration {
    override val fromVersion: Int = 6
    override val toVersion: Int = 7

    override fun migrate(document: JsonObject): JsonObject =
        JsonObject(document + ("schemaVersion" to JsonPrimitive(toVersion)))
}

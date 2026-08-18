package viewforge.project.migrations

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import viewforge.project.Migration

/**
 * Schema v3 -> v4 (ADR-034 Amendment, #255): makes read-only screen state **recursive** so a record field can
 * itself be a nested list-of-record. Unlike [M1to2]/[M2to3] (version stamps), this migration **transforms** the
 * document, because the recursive model changes the serialized shape of existing v3 state:
 *
 * - A `listOfRecord` type's fields change from `{"name":…, "scalar":"STRING"}` to
 *   `{"name":…, "type":{"kind":"scalar", "scalar":"STRING"}}` — a [viewforge.model.RecordField] now holds a full
 *   [viewforge.model.StateType].
 * - A `rows` sample's cells change from a bare primitive (`"Ada"`) to `{"kind":"scalar", "value":"Ada"}` — a cell
 *   is now a [viewforge.model.SampleValue].
 *
 * A v3 document has no *nested* lists (that capability is what v4 adds), so exactly one level of wrapping is
 * needed; the transform is idempotent in shape (already-wrapped v4 input is not produced by a v3 file). Only
 * `screens[].state[]` carries state in v3 (components have none — component-local state is deferred), so nothing
 * else is touched. A v3 document with no state migrates by version stamp alone, exactly like M2to3.
 */
object M3to4 : Migration {
    override val fromVersion: Int = 3
    override val toVersion: Int = 4

    override fun migrate(document: JsonObject): JsonObject {
        val screens = document["screens"] as? JsonArray ?: return stamp(document)
        val migrated = JsonArray(screens.map { migrateScreen(it) })
        return stamp(JsonObject(document + ("screens" to migrated)))
    }

    private fun stamp(document: JsonObject): JsonObject =
        JsonObject(document + ("schemaVersion" to JsonPrimitive(toVersion)))

    private fun migrateScreen(screen: JsonElement): JsonElement {
        if (screen !is JsonObject) return screen
        val state = screen["state"] as? JsonArray ?: return screen
        val migrated = JsonArray(state.map { migrateStateField(it) })
        return JsonObject(screen + ("state" to migrated))
    }

    private fun migrateStateField(field: JsonElement): JsonElement {
        if (field !is JsonObject) return field
        val out = field.toMutableMap()
        (field["type"] as? JsonObject)?.let { out["type"] = migrateType(it) }
        (field["sample"] as? JsonObject)?.let { out["sample"] = migrateSample(it) }
        return JsonObject(out)
    }

    /** Rewrite a `listOfRecord` type's flat `{name, scalar}` fields to `{name, type:{kind:"scalar", scalar}}`. */
    private fun migrateType(type: JsonObject): JsonObject {
        if ((type["kind"] as? JsonPrimitive)?.content != "listOfRecord") return type
        val fields = type["fields"] as? JsonArray ?: return type
        val migrated = JsonArray(
            fields.map { field ->
                if (field !is JsonObject) {
                    field
                } else {
                    val scalar = field["scalar"]
                    if (scalar == null) {
                        field // already a v4-shaped record field
                    } else {
                        buildJsonObject {
                            field["name"]?.let { put("name", it) }
                            put(
                                "type",
                                buildJsonObject {
                                    put("kind", JsonPrimitive("scalar"))
                                    put("scalar", scalar)
                                },
                            )
                        }
                    }
                }
            },
        )
        return JsonObject(type + ("fields" to migrated))
    }

    /** Rewrite a `rows` sample so each cell primitive becomes `{kind:"scalar", value:<primitive>}`. */
    private fun migrateSample(sample: JsonObject): JsonObject {
        if ((sample["kind"] as? JsonPrimitive)?.content != "rows") return sample
        val rows = sample["rows"] as? JsonArray ?: return sample
        val migrated = JsonArray(
            rows.map { row ->
                if (row !is JsonObject) {
                    row
                } else {
                    JsonObject(
                        row.mapValues { (_, cell) ->
                            if (cell is JsonObject && cell["kind"] != null) {
                                cell // already a v4-shaped cell
                            } else {
                                buildJsonObject {
                                    put("kind", JsonPrimitive("scalar"))
                                    put("value", cell)
                                }
                            }
                        },
                    )
                }
            },
        )
        return JsonObject(sample + ("rows" to migrated))
    }
}

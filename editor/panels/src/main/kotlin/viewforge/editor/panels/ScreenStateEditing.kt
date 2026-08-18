package viewforge.editor.panels

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.RecordField
import viewforge.model.SampleValue
import viewforge.model.ScalarType
import viewforge.model.StateField
import viewforge.model.StateType
import viewforge.model.isBindingIdentifier
import viewforge.model.scalarOrNull

/**
 * Pure, Compose-free logic behind the screen-state editor (ADR-034, #21): parsing typed sample literals,
 * retyping a field, and keeping a list's sample rows consistent with its record shape. Kept here so the
 * fiddly parts are unit-tested without a UI harness; the `@Composable` editor in `ScreenStateSection.kt`
 * stays thin over these. Nothing here evaluates anything — samples are typed literals (PF-4).
 */

/** The zero value for a [type]: `""`, `0`, `0.0`, `false`. Used to seed new fields, cells, and rows. */
internal fun scalarDefault(type: ScalarType): JsonPrimitive = when (type) {
    ScalarType.STRING -> JsonPrimitive("")
    ScalarType.INT -> JsonPrimitive(0)
    ScalarType.FLOAT -> JsonPrimitive(0.0)
    ScalarType.BOOL -> JsonPrimitive(false)
}

/**
 * Parse [text] as a scalar of [type], or null if it is not a valid literal of that type (a non-number for
 * INT/FLOAT, a non-boolean for BOOL). STRING always parses. The editor withholds the commit on null, keeping
 * the field's prior sample, exactly like the numeric prop controls (I8).
 */
internal fun parseScalar(text: String, type: ScalarType): JsonPrimitive? = when (type) {
    ScalarType.STRING -> JsonPrimitive(text)
    ScalarType.INT -> text.trim().toIntOrNull()?.let { JsonPrimitive(it) }
    ScalarType.FLOAT -> text.trim().toDoubleOrNull()?.let { JsonPrimitive(it) }
    ScalarType.BOOL -> text.trim().toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
}

/** Retype a scalar [field] to [type], resetting its sample to that type's default (the old literal may not fit). */
internal fun retypeScalar(field: StateField, type: ScalarType): StateField =
    field.copy(type = StateType.Scalar(type), sample = SampleValue.Scalar(scalarDefault(type)))

/**
 * Rebuild [rows] to match [fields]: keep only cells whose key is a current field, and add a default cell for
 * every field a row is missing. Run after any record-field add/remove/rename so the sample never drifts out of
 * shape from the declared record.
 */
internal fun reconcileRows(
    rows: List<Map<String, JsonPrimitive>>,
    fields: List<RecordField>,
): List<Map<String, JsonPrimitive>> = rows.map { row ->
    fields.associate {
        it.name to
            (row[it.name] ?: scalarDefault(it.scalarOrNull ?: ScalarType.STRING))
    }
}

/** A fresh row with a default cell per record field. */
internal fun emptyRow(fields: List<RecordField>): Map<String, JsonPrimitive> =
    fields.associate { it.name to scalarDefault(it.scalarOrNull ?: ScalarType.STRING) }

/**
 * The flat scalar-cell view of a list field's [sample] rows (nested cells dropped): slice-A editing is
 * scalar-only, so the composable editor works over `Map<String, JsonPrimitive>` and re-wraps via
 * [viewforge.model.scalarRows]. Nested-row editing arrives in #259 and will read the full [SampleValue] rows.
 */
internal fun scalarRowsView(sample: SampleValue): List<Map<String, JsonPrimitive>> =
    (sample as? SampleValue.Rows)?.rows.orEmpty().map { row ->
        row.mapNotNull { (k, v) -> (v as? SampleValue.Scalar)?.let { k to it.value } }.toMap()
    }

/**
 * Whether [name] is a legal, non-duplicate name for the state field at [index] among [fields] — a binding
 * identifier (GC-3) not already used by a *different* field. The editor commits a rename only when this holds,
 * so an invalid or colliding name never reaches a command.
 */
internal fun isValidStateName(name: String, index: Int, fields: List<StateField>): Boolean =
    isBindingIdentifier(name) && fields.withIndex().none { (i, f) -> i != index && f.name == name }

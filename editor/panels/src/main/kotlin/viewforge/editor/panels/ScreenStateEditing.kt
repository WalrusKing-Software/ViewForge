package viewforge.editor.panels

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.RecordField
import viewforge.model.SampleValue
import viewforge.model.ScalarType
import viewforge.model.StateField
import viewforge.model.StateType
import viewforge.model.isBindingIdentifier

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

/** A default sample cell for [field]: the scalar zero value, or empty sub-rows for a nested list (#255). */
internal fun defaultCell(field: RecordField): SampleValue = when (val t = field.type) {
    is StateType.Scalar -> SampleValue.Scalar(scalarDefault(t.scalar))
    is StateType.ListOfRecord -> SampleValue.Rows(emptyList())
}

/** A fresh sample row: a [defaultCell] per record field. */
internal fun emptySampleRow(fields: List<RecordField>): Map<String, SampleValue> =
    fields.associate { it.name to defaultCell(it) }

/**
 * Reconcile [rows] to [fields] **recursively** (nested lists, #255): keep only cells whose key is a current
 * field, seed a default for any a row is missing, and — for a nested list field — reconcile each cell's own
 * sub-rows to the field's sub-shape. Run after any record-shape edit so the sample never drifts out of shape.
 */
internal fun reconcileSampleRows(
    rows: List<Map<String, SampleValue>>,
    fields: List<RecordField>,
): List<Map<String, SampleValue>> = rows.map { row ->
    fields.associate { f -> f.name to reconcileCell(row[f.name], f) }
}

/** Coerce [cell] to [field]'s shape: a scalar default/kept, or a nested-list cell reconciled to the sub-shape. */
private fun reconcileCell(cell: SampleValue?, field: RecordField): SampleValue = when (val t = field.type) {
    is StateType.Scalar -> cell as? SampleValue.Scalar ?: SampleValue.Scalar(scalarDefault(t.scalar))
    is StateType.ListOfRecord ->
        SampleValue.Rows(reconcileSampleRows((cell as? SampleValue.Rows)?.rows.orEmpty(), t.fields))
}

/**
 * Whether [name] is a legal, non-duplicate name for the state field at [index] among [fields] — a binding
 * identifier (GC-3) not already used by a *different* field. The editor commits a rename only when this holds,
 * so an invalid or colliding name never reaches a command.
 */
internal fun isValidStateName(name: String, index: Int, fields: List<StateField>): Boolean =
    isBindingIdentifier(name) && fields.withIndex().none { (i, f) -> i != index && f.name == name }

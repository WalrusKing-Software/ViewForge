package viewforge.editor.panels

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import viewforge.editor.state.EditorState
import viewforge.model.Node
import viewforge.model.RecordField
import viewforge.model.Repeater
import viewforge.model.SampleValue
import viewforge.model.ScalarType
import viewforge.model.StateField
import viewforge.model.StateType

/**
 * The screen-state editor (ADR-034, #21): declare and edit a screen's read-only [StateField]s — the data
 * its props bind to. Shown in the inspector when nothing is selected, so it is screen-scoped (a node's own
 * props are the inspector's other mode). Every edit runs an undoable command through [EditorState]; there is
 * no per-component code here, matching the rest of the inspector.
 *
 * Scalars get a type + one typed sample literal; a list-of-record gets an editable record shape and sample
 * rows (what a `vforge.repeat` iterates). Samples are typed literals only, never expressions (PF-4).
 */
@Composable
internal fun ScreenStateSection(state: EditorState) {
    val screen = state.activeScreen
    PanelColumn(Modifier.padding(bottom = 12.dp)) {
        SectionLabel("Screen State")
        if (screen == null) {
            MutedText("No screen to hold data.")
            return@PanelColumn
        }
        Text(
            "Read-only data this screen's props can bind to.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val fields = screen.state
        if (fields.isEmpty()) MutedText("No data declared.")
        fields.forEachIndexed { index, field -> StateFieldCard(state, index, field, fields) }
        Row(Modifier.padding(top = 6.dp)) {
            ActionText("+ value") { state.addScalarStateField() }
            ActionText("+ list") { state.addListStateField() }
        }
    }
}

@Composable
private fun StateFieldCard(state: EditorState, index: Int, field: StateField, all: List<StateField>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                NameField(field.name) { renamed -> commitRename(state, index, field, renamed, all) }
            }
            Box(Modifier.width(96.dp).padding(start = 6.dp)) {
                when (val type = field.type) {
                    is StateType.Scalar -> ScalarTypeDropdown(type.scalar) { picked ->
                        state.updateStateField(index, retypeScalar(field, picked))
                    }
                    is StateType.ListOfRecord -> Text(
                        "list",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ActionText("✕") { state.removeStateField(field.name) }
        }
        when (val type = field.type) {
            is StateType.Scalar -> ScalarSample(field.sample, type.scalar) { sample ->
                state.updateStateField(index, field.copy(sample = SampleValue.Scalar(sample)))
            }
            is StateType.ListOfRecord -> ListSample(state, index, field, type)
        }
    }
}

// --- scalar ---------------------------------------------------------------------------------------

@Composable
private fun ScalarSample(
    sample: SampleValue,
    scalar: ScalarType,
    onChange: (kotlinx.serialization.json.JsonPrimitive) -> Unit,
) {
    val current = (sample as? SampleValue.Scalar)?.value
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "sample",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        if (scalar == ScalarType.BOOL) {
            Switch(checked = current?.content?.toBooleanStrictOrNull() ?: false, onCheckedChange = {
                onChange(kotlinx.serialization.json.JsonPrimitive(it))
            })
        } else {
            ScalarTextField(current?.content ?: "", scalar, onChange)
        }
    }
}

/** A text field that commits [onChange] only when the input parses as [scalar] (withholds on an invalid number). */
@Composable
private fun ScalarTextField(
    current: String,
    scalar: ScalarType,
    onChange: (kotlinx.serialization.json.JsonPrimitive) -> Unit,
) {
    var text by remember(current) { mutableStateOf(current) }
    var invalid by remember(current) { mutableStateOf(false) }
    FieldBox(error = invalid) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                val parsed = parseScalar(it, scalar)
                invalid = parsed == null
                if (parsed != null) onChange(parsed)
            },
            singleLine = true,
            textStyle = fieldStyle(),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// --- list of record -------------------------------------------------------------------------------

@Composable
private fun ListSample(state: EditorState, index: Int, field: StateField, type: StateType.ListOfRecord) {
    val rows = (field.sample as? SampleValue.Rows)?.rows.orEmpty()

    // The record shape.
    Text(
        "record fields",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    type.fields.forEachIndexed { fi, rf ->
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                NameField(rf.name) { newName ->
                    if (isBindingName(newName) && type.fields.none { it.name == newName }) {
                        val newFields = type.fields.toMutableList().apply { this[fi] = rf.copy(name = newName) }
                        val remapped = rows.map { r -> r.mapKeys { if (it.key == rf.name) newName else it.key } }
                        state.updateStateField(
                            index,
                            field.copy(
                                type = type.copy(fields = newFields),
                                sample = SampleValue.Rows(reconcileRows(remapped, newFields)),
                            ),
                        )
                    }
                }
            }
            Box(Modifier.width(96.dp).padding(start = 6.dp)) {
                ScalarTypeDropdown(rf.scalar) { picked ->
                    val newFields = type.fields.toMutableList().apply { this[fi] = rf.copy(scalar = picked) }
                    state.updateStateField(
                        index,
                        field.copy(
                            type = type.copy(fields = newFields),
                            sample = SampleValue.Rows(reconcileRows(rows, newFields)),
                        ),
                    )
                }
            }
            if (type.fields.size > 1) {
                ActionText("✕") {
                    val newFields = type.fields.filterIndexed { i, _ -> i != fi }
                    state.updateStateField(
                        index,
                        field.copy(
                            type = type.copy(fields = newFields),
                            sample = SampleValue.Rows(reconcileRows(rows, newFields)),
                        ),
                    )
                }
            }
        }
    }
    ActionText("+ field") {
        val name = uniqueRecordFieldName(type.fields)
        val newFields = type.fields + RecordField(name, ScalarType.STRING)
        state.updateStateField(
            index,
            field.copy(type = type.copy(fields = newFields), sample = SampleValue.Rows(reconcileRows(rows, newFields))),
        )
    }

    // The sample rows.
    Text(
        "sample rows",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    rows.forEachIndexed { ri, row ->
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            type.fields.forEach { rf ->
                Box(Modifier.weight(1f).padding(end = 4.dp)) {
                    if (rf.scalar == ScalarType.BOOL) {
                        Switch(
                            checked = row[rf.name]?.content?.toBooleanStrictOrNull() ?: false,
                            onCheckedChange = { on ->
                                state.updateStateField(
                                    index,
                                    field.copy(
                                        sample = SampleValue.Rows(
                                            putCell(rows, ri, rf.name, kotlinx.serialization.json.JsonPrimitive(on)),
                                        ),
                                    ),
                                )
                            },
                        )
                    } else {
                        ScalarTextField(row[rf.name]?.content ?: "", rf.scalar) { cell ->
                            state.updateStateField(
                                index,
                                field.copy(sample = SampleValue.Rows(putCell(rows, ri, rf.name, cell))),
                            )
                        }
                    }
                }
            }
            ActionText("✕") {
                state.updateStateField(
                    index,
                    field.copy(
                        sample = SampleValue.Rows(
                            rows.filterIndexed { i, _ ->
                                i != ri
                            },
                        ),
                    ),
                )
            }
        }
    }
    ActionText("+ row") {
        state.updateStateField(index, field.copy(sample = SampleValue.Rows(rows + emptyRow(type.fields))))
    }
}

// --- the vforge.repeat source picker --------------------------------------------------------------

/**
 * The inspector body for a `vforge.repeat` node (ADR-034): a single picker binding its `source` to a
 * list-of-record screen field. A node-type special case beside the user-component one — not per-component UI.
 */
@Composable
internal fun RepeaterSource(state: EditorState, node: Node) {
    val current = Repeater.sourceOf(node)
    val lists = state.listStateFields
    if (lists.isEmpty()) {
        MutedText("Declare a list field in Screen State first.")
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "source",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        var open by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
            FieldBox(onClick = { open = true }) {
                Text(
                    current?.takeIf {
                        it.isNotEmpty()
                    } ?: "—",
                    style = fieldStyle(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                lists.forEach { listField ->
                    DropdownMenuItem(
                        text = { Text(listField.name, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            state.setProp(
                                node.id,
                                Repeater.SOURCE_PROP,
                                viewforge.model.PropValue.StateBinding(listField.name),
                            )
                            open = false
                        },
                    )
                }
            }
        }
    }
}

// --- shared small controls ------------------------------------------------------------------------

/** A name text field that reflects [current] and commits typed input via [onCommit] (the caller validates). */
@Composable
private fun NameField(current: String, onCommit: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    val valid = isBindingName(text)
    FieldBox(error = !valid) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                onCommit(it)
            },
            singleLine = true,
            textStyle = fieldStyle(),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ScalarTypeDropdown(current: ScalarType, onPick: (ScalarType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FieldBox(onClick = { open = true }) {
            Text(scalarLabel(current), style = fieldStyle(), modifier = Modifier.fillMaxWidth())
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ScalarType.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(scalarLabel(option), style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onPick(option)
                        open = false
                    },
                )
            }
        }
    }
}

private fun scalarLabel(type: ScalarType): String = when (type) {
    ScalarType.STRING -> "String"
    ScalarType.INT -> "Int"
    ScalarType.FLOAT -> "Float"
    ScalarType.BOOL -> "Bool"
}

private fun isBindingName(s: String): Boolean = viewforge.model.isBindingIdentifier(s)

/** Commit a field rename only when the new name is a legal, non-duplicate binding identifier (GC-3). */
private fun commitRename(state: EditorState, index: Int, field: StateField, name: String, all: List<StateField>) {
    if (isValidStateName(name, index, all)) state.updateStateField(index, field.copy(name = name))
}

private fun uniqueRecordFieldName(fields: List<RecordField>): String {
    val taken = fields.mapTo(HashSet()) { it.name }
    if ("field" !in taken) return "field"
    var n = 2
    while ("field$n" in taken) n++
    return "field$n"
}

/** Set the cell [key] of row [ri] to [value], returning a new rows list. */
private fun putCell(
    rows: List<Map<String, kotlinx.serialization.json.JsonPrimitive>>,
    ri: Int,
    key: String,
    value: kotlinx.serialization.json.JsonPrimitive,
): List<Map<String, kotlinx.serialization.json.JsonPrimitive>> =
    rows.mapIndexed { i, row -> if (i == ri) row + (key to value) else row }

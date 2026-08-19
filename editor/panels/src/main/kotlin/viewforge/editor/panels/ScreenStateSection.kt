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
import viewforge.model.Dropdown
import viewforge.model.Node
import viewforge.model.RecordField
import viewforge.model.Repeater
import viewforge.model.SampleValue
import viewforge.model.ScalarType
import viewforge.model.StateField
import viewforge.model.StateType

/**
 * The state editor (ADR-034, #21 + the component-local state Amendment): declare and edit the read-only
 * [StateField]s of the **active edit surface** — the open component's own state when one is being edited, else
 * the active screen's — the data its props bind to. Shown in the inspector when nothing is selected (a node's
 * own props are the inspector's other mode). Every edit runs an undoable, owner-agnostic command through
 * [EditorState], so there is no per-surface code here, matching the rest of the inspector.
 *
 * Scalars get a type + one typed sample literal; a list-of-record gets an editable record shape and sample
 * rows (what a `vforge.repeat` iterates). Samples are typed literals only, never expressions (PF-4).
 */
@Composable
internal fun ScreenStateSection(state: EditorState) {
    val isComponent = state.editingComponentId != null
    PanelColumn(Modifier.padding(bottom = 12.dp)) {
        SectionLabel(if (isComponent) "Component State" else "Screen State")
        if (state.activeEditRootId == null) {
            MutedText("No surface to hold data.")
            return@PanelColumn
        }
        Text(
            if (isComponent) {
                "Read-only data this component's props can bind to."
            } else {
                "Read-only data this screen's props can bind to."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val fields = state.activeScreenStateForRender
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
    RecordEditor(type.fields, rows, depth = 0) { newFields, newRows ->
        state.updateStateField(
            index,
            field.copy(type = type.copy(fields = newFields), sample = SampleValue.Rows(newRows)),
        )
    }
}

/**
 * A recursive record editor (nested lists, #255): the record's shape (each field a scalar **or** a nested
 * list-of-record) and its sample rows, value-hoisted so it composes at any depth. Any edit emits the new
 * (fields, rows), reconciled so the sample never drifts from the shape ([reconcileSampleRows]). A nested list
 * field edits its sub-shape here and its per-row sub-data in [CellEditor].
 */
@Composable
private fun RecordEditor(
    fields: List<RecordField>,
    rows: List<Map<String, SampleValue>>,
    depth: Int,
    onChange: (List<RecordField>, List<Map<String, SampleValue>>) -> Unit,
) {
    SubLabel("record fields")
    fields.forEachIndexed { fi, rf ->
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                NameField(rf.name) { newName ->
                    if (isBindingName(newName) && fields.none { it.name == newName }) {
                        val nf = fields.replaceAt(fi, rf.copy(name = newName))
                        val remapped = rows.map { r -> r.mapKeys { if (it.key == rf.name) newName else it.key } }
                        onChange(nf, reconcileSampleRows(remapped, nf))
                    }
                }
            }
            Box(Modifier.width(96.dp).padding(start = 6.dp)) {
                FieldTypePicker(rf.type) { newType ->
                    val nf = fields.replaceAt(fi, RecordField(rf.name, newType))
                    onChange(nf, reconcileSampleRows(rows, nf))
                }
            }
            if (fields.size > 1) {
                ActionText("✕") {
                    val nf = fields.filterIndexed { i, _ -> i != fi }
                    onChange(nf, reconcileSampleRows(rows, nf))
                }
            }
        }
        // A nested list field edits its sub-shape one level in; its per-row data is edited in the rows below.
        (rf.type as? StateType.ListOfRecord)?.let { sub ->
            Column(Modifier.padding(start = 12.dp)) {
                NestedShapeEditor(sub.fields, depth + 1) { subFields ->
                    val nf = fields.replaceAt(fi, RecordField(rf.name, StateType.ListOfRecord(subFields)))
                    onChange(nf, reconcileSampleRows(rows, nf))
                }
            }
        }
    }
    ActionText("+ field") {
        val nf = fields + RecordField(uniqueRecordFieldName(fields), ScalarType.STRING)
        onChange(nf, reconcileSampleRows(rows, nf))
    }

    SubLabel("sample rows")
    val scalarFields = fields.filter { it.type is StateType.Scalar }
    val listFields = fields.filter { it.type is StateType.ListOfRecord }
    rows.forEachIndexed { ri, row ->
        // Scalar cells sit in a compact row; nested-list cells render full-width beneath (they are sub-tables).
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            scalarFields.forEach { rf ->
                Box(Modifier.weight(1f).padding(end = 4.dp)) {
                    CellEditor(rf, row[rf.name], depth) { cell ->
                        onChange(
                            fields,
                            rows.replaceAt(
                                ri,
                                row + (rf.name to cell),
                            ),
                        )
                    }
                }
            }
            ActionText("✕") { onChange(fields, rows.filterIndexed { i, _ -> i != ri }) }
        }
        listFields.forEach { rf ->
            Column(Modifier.padding(start = 12.dp)) {
                MutedText("${rf.name} (row ${ri + 1})")
                CellEditor(rf, row[rf.name], depth) { cell ->
                    onChange(
                        fields,
                        rows.replaceAt(
                            ri,
                            row + (rf.name to cell),
                        ),
                    )
                }
            }
        }
    }
    ActionText("+ row") { onChange(fields, rows + emptySampleRow(fields)) }
}

/** The shape half of a nested list field (its sub-fields), edited without rows — data is edited per parent row. */
@Composable
private fun NestedShapeEditor(fields: List<RecordField>, depth: Int, onChange: (List<RecordField>) -> Unit) {
    SubLabel("fields")
    fields.forEachIndexed { fi, rf ->
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                NameField(rf.name) { newName ->
                    if (isBindingName(newName) && fields.none { it.name == newName }) {
                        onChange(fields.replaceAt(fi, rf.copy(name = newName)))
                    }
                }
            }
            Box(Modifier.width(96.dp).padding(start = 6.dp)) {
                FieldTypePicker(rf.type) { newType -> onChange(fields.replaceAt(fi, RecordField(rf.name, newType))) }
            }
            if (fields.size > 1) ActionText("✕") { onChange(fields.filterIndexed { i, _ -> i != fi }) }
        }
        (rf.type as? StateType.ListOfRecord)?.let { sub ->
            Column(Modifier.padding(start = 12.dp)) {
                NestedShapeEditor(sub.fields, depth + 1) { subFields ->
                    onChange(fields.replaceAt(fi, RecordField(rf.name, StateType.ListOfRecord(subFields))))
                }
            }
        }
    }
    ActionText("+ field") { onChange(fields + RecordField(uniqueRecordFieldName(fields), ScalarType.STRING)) }
}

/** One sample cell: a scalar control, or — for a nested list field — a sub-rows editor over its sub-shape. */
@Composable
private fun CellEditor(field: RecordField, cell: SampleValue?, depth: Int, onChange: (SampleValue) -> Unit) {
    when (val t = field.type) {
        is StateType.Scalar -> {
            val value = (cell as? SampleValue.Scalar)?.value
            if (t.scalar == ScalarType.BOOL) {
                Switch(
                    checked = value?.content?.toBooleanStrictOrNull() ?: false,
                    onCheckedChange = { onChange(SampleValue.Scalar(kotlinx.serialization.json.JsonPrimitive(it))) },
                )
            } else {
                ScalarTextField(value?.content ?: "", t.scalar) { onChange(SampleValue.Scalar(it)) }
            }
        }
        is StateType.ListOfRecord -> {
            val sub = (cell as? SampleValue.Rows)?.rows.orEmpty()
            SubRowsEditor(t.fields, sub, depth + 1) { onChange(SampleValue.Rows(it)) }
        }
    }
}

/** The data half of a nested list cell: its sub-rows over a fixed sub-shape (recursing for deeper nesting). */
@Composable
private fun SubRowsEditor(
    fields: List<RecordField>,
    rows: List<Map<String, SampleValue>>,
    depth: Int,
    onChange: (List<Map<String, SampleValue>>) -> Unit,
) {
    val scalarFields = fields.filter { it.type is StateType.Scalar }
    val listFields = fields.filter { it.type is StateType.ListOfRecord }
    rows.forEachIndexed { ri, row ->
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            scalarFields.forEach { rf ->
                Box(Modifier.weight(1f).padding(end = 4.dp)) {
                    CellEditor(rf, row[rf.name], depth) { cell ->
                        onChange(rows.replaceAt(ri, row + (rf.name to cell)))
                    }
                }
            }
            ActionText("✕") { onChange(rows.filterIndexed { i, _ -> i != ri }) }
        }
        listFields.forEach { rf ->
            Column(Modifier.padding(start = 12.dp)) {
                MutedText(rf.name)
                CellEditor(rf, row[rf.name], depth) { cell -> onChange(rows.replaceAt(ri, row + (rf.name to cell))) }
            }
        }
    }
    ActionText("+ row") { onChange(rows + emptySampleRow(fields)) }
}

/** A field type picker: the scalar types plus **List** (a nested list-of-record), for building nested state (#255). */
@Composable
private fun FieldTypePicker(current: StateType, onPick: (StateType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FieldBox(onClick = { open = true }) {
            Text(fieldTypeLabel(current), style = fieldStyle(), modifier = Modifier.fillMaxWidth())
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ScalarType.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(scalarLabel(option), style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onPick(StateType.Scalar(option))
                        open = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("List", style = MaterialTheme.typography.bodySmall) },
                onClick = {
                    // A fresh nested list defaults to one String sub-field, mirroring `+ list` at the top level.
                    if (current !is StateType.ListOfRecord) {
                        onPick(StateType.ListOfRecord(listOf(RecordField("field", ScalarType.STRING))))
                    }
                    open = false
                },
            )
        }
    }
}

private fun fieldTypeLabel(type: StateType): String = when (type) {
    is StateType.Scalar -> scalarLabel(type.scalar)
    is StateType.ListOfRecord -> "List"
}

/** A muted section sub-label used inside a state card (record fields / sample rows). */
@Composable
private fun SubLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Return a copy of this list with [index] replaced by [value]. */
private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> = toMutableList().apply { this[index] = value }

// --- the vforge.repeat source picker --------------------------------------------------------------

/**
 * The inspector body for a `vforge.repeat` node (ADR-034): a single picker binding its `source` to a
 * list-of-record screen field. A node-type special case beside the user-component one — not per-component UI.
 */
@Composable
internal fun RepeaterSource(state: EditorState, node: Node) {
    val current = Repeater.sourceOf(node)
    // Top-level list fields plus, when this repeat is nested inside another, the outer row's `item.<listField>`s.
    val sources = state.listSourceChoices(node)
    if (sources.isEmpty()) {
        MutedText("Declare a list field in State first.")
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
                sources.forEach { source ->
                    DropdownMenuItem(
                        text = { Text(source, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            state.setProp(node.id, Repeater.SOURCE_PROP, viewforge.model.PropValue.StateBinding(source))
                            open = false
                        },
                    )
                }
            }
        }
    }
    RepeaterLayoutPicker(state, node)
}

/**
 * The `vforge.repeat` layout picker (ADR-034 slice 2, #251): inline `forEach` (default) or a scrolling
 * `LazyColumn`. Picking the default clears the additive [Repeater.LAYOUT_PROP] so the node stays byte-clean.
 */
@Composable
private fun RepeaterLayoutPicker(state: EditorState, node: Node) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "layout",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        var open by remember { mutableStateOf(false) }
        val current = Repeater.layoutOf(node)
        Box(Modifier.weight(1f)) {
            FieldBox(onClick = { open = true }) {
                Text(repeatLayoutLabel(current), style = fieldStyle(), modifier = Modifier.fillMaxWidth())
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                listOf(Repeater.LAYOUT_FOR_EACH, Repeater.LAYOUT_LAZY_COLUMN).forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(repeatLayoutLabel(mode), style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            // Default (forEach) clears the prop; lazyColumn stores an explicit literal.
                            val value = if (mode == Repeater.LAYOUT_LAZY_COLUMN) {
                                viewforge.model.PropValue.Literal(kotlinx.serialization.json.JsonPrimitive(mode))
                            } else {
                                null
                            }
                            state.setProp(node.id, Repeater.LAYOUT_PROP, value)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

private fun repeatLayoutLabel(mode: String): String = when (mode) {
    Repeater.LAYOUT_LAZY_COLUMN -> "Scrolling (LazyColumn)"
    else -> "Inline (forEach)"
}

// --- the vforge.dropdown options picker -----------------------------------------------------------

/**
 * The inspector body for a `vforge.dropdown` node (ADR-034 slice 2, #253): bind its `options` to a
 * list-of-record screen field, then pick which record field is shown per option ([Dropdown.LABEL_PROP]).
 * Binding options defaults the label to the list's first field so the node is immediately generatable; the
 * label picker only appears once options are bound. A node-type special case, like [RepeaterSource].
 */
@Composable
internal fun DropdownSource(state: EditorState, node: Node) {
    val current = Dropdown.optionsOf(node)?.takeIf { it.isNotEmpty() }
    val lists = state.listStateFields
    if (lists.isEmpty()) {
        MutedText("Declare a list field in State first.")
        return
    }
    StatePickerRow("options", current ?: "—") { open ->
        lists.forEach { listField ->
            DropdownMenuItem(
                text = { Text(listField.name, style = MaterialTheme.typography.bodySmall) },
                onClick = {
                    state.setProp(
                        node.id,
                        Dropdown.OPTIONS_PROP,
                        viewforge.model.PropValue.StateBinding(listField.name),
                    )
                    // Default the shown field to the list's first record field, so the dropdown generates
                    // without a second step; the user can retarget it via the label picker below.
                    val firstField = (listField.type as? StateType.ListOfRecord)?.fields?.firstOrNull()?.name
                    state.setProp(node.id, Dropdown.LABEL_PROP, firstField?.let { labelLiteral(it) })
                    open.value = false
                },
            )
        }
    }
    val boundFields = current
        ?.let { path -> lists.firstOrNull { it.name == path } }
        ?.let { (it.type as? StateType.ListOfRecord)?.fields }
        .orEmpty()
    if (boundFields.isNotEmpty()) DropdownLabelPicker(state, node, boundFields)
}

/** The record field shown per option: one of the bound list's fields, stored as a literal in [Dropdown.LABEL_PROP]. */
@Composable
private fun DropdownLabelPicker(state: EditorState, node: Node, fields: List<RecordField>) {
    StatePickerRow("label", Dropdown.labelFieldOf(node) ?: "—") { open ->
        fields.forEach { field ->
            DropdownMenuItem(
                text = { Text(field.name, style = MaterialTheme.typography.bodySmall) },
                onClick = {
                    state.setProp(node.id, Dropdown.LABEL_PROP, labelLiteral(field.name))
                    open.value = false
                },
            )
        }
    }
}

private fun labelLiteral(field: String) =
    viewforge.model.PropValue.Literal(kotlinx.serialization.json.JsonPrimitive(field))

/**
 * A labelled picker row (label column + a click-to-open [FieldBox] anchoring a [DropdownMenu]) shared by the
 * dropdown's options/label pickers. [menuItems] receives the open-state so an item can close the menu on pick.
 */
@Composable
private fun StatePickerRow(
    label: String,
    display: String,
    menuItems: @Composable (open: androidx.compose.runtime.MutableState<Boolean>) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        val open = remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
            FieldBox(onClick = { open.value = true }) {
                Text(display, style = fieldStyle(), modifier = Modifier.fillMaxWidth())
            }
            DropdownMenu(expanded = open.value, onDismissRequest = { open.value = false }) {
                menuItems(open)
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

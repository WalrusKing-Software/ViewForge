package viewforge.editor.panels

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
import kotlinx.serialization.json.JsonPrimitive
import viewforge.editor.state.ActionKind
import viewforge.editor.state.EditorState
import viewforge.editor.state.availableActionKinds
import viewforge.editor.state.defaultActionFor
import viewforge.editor.state.isEditable
import viewforge.editor.state.kind
import viewforge.editor.state.retarget
import viewforge.editor.state.scalarValueOf
import viewforge.editor.state.targetsFor
import viewforge.editor.state.valueScalarType
import viewforge.editor.state.withScalarValue
import viewforge.model.Action
import viewforge.model.EventSlotDefinition
import viewforge.model.Node
import viewforge.model.ScalarType
import viewforge.model.Screen

/**
 * The data-driven **action editor** (ADR-035, #277): for each event slot the catalog declares on the selected
 * node (e.g. a Button's `onClick`), edit the ordered [Action] list. Every action is a closed, structured
 * operation — pick a kind, then a **writable target from the surface's declared state** (never free text), then
 * a typed literal value — so there is no expression input anywhere (PF-4). Each edit runs the undoable
 * [EditorState.setHandler] command; the section is generated from the slot metadata, so a component's events
 * need no per-component UI (the I1 anti-pattern), exactly like the prop rows.
 *
 * This slice edits the scalar/navigation kinds ([ActionKind.isEditable]); a list action (`AppendRow`/`RemoveRow`)
 * is shown read-only with a remove button until its multi-cell editor lands.
 */
@Composable
internal fun EventSlotsEditor(state: EditorState, node: Node, slots: List<EventSlotDefinition>) {
    val fields = state.activeScreenStateForRender
    val screens = state.navigableScreens()
    slots.forEach { slot ->
        val actions = node.handlers[slot.name].orEmpty()
        SlotEditor(slot, actions, fields, screens) { newActions ->
            state.setHandler(node.id, slot.name, newActions)
        }
    }
}

@Composable
private fun SlotEditor(
    slot: EventSlotDefinition,
    actions: List<Action>,
    fields: List<viewforge.model.StateField>,
    screens: List<Screen>,
    onChange: (List<Action>) -> Unit,
) {
    val kinds = availableActionKinds(fields, screens.isNotEmpty())
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(slot.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (kinds.isNotEmpty()) {
                ActionText("+ action") {
                    defaultActionFor(kinds.first(), fields, screens.map { it.id })?.let { onChange(actions + it) }
                }
            }
        }
        if (kinds.isEmpty()) {
            MutedText("Declare state (or add a screen) to attach actions.")
            return@Column
        }
        if (actions.isEmpty()) MutedText("No actions — this handler does nothing.")
        actions.forEachIndexed { index, action ->
            ActionRow(
                action = action,
                kinds = kinds,
                fields = fields,
                screens = screens,
                canMoveUp = index > 0,
                canMoveDown = index < actions.lastIndex,
                onChange = { updated -> onChange(actions.replaceAt(index, updated)) },
                onRemove = { onChange(actions.filterIndexed { i, _ -> i != index }) },
                onMoveUp = { onChange(actions.swap(index, index - 1)) },
                onMoveDown = { onChange(actions.swap(index, index + 1)) },
            )
        }
    }
}

@Composable
private fun ActionRow(
    action: Action,
    kinds: List<ActionKind>,
    fields: List<viewforge.model.StateField>,
    screens: List<Screen>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (Action) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Kind: switching kind replaces the action with a fresh default of that kind.
            Box(Modifier.width(96.dp)) {
                Picker(action.kind.label, kinds.map { it.label to it }) { picked ->
                    defaultActionFor(picked, fields, screens.map { it.id })?.let(onChange)
                }
            }
            // Target: a writable state field (or a screen for Navigate).
            Box(Modifier.weight(1f).padding(start = 4.dp)) {
                TargetPicker(action, fields, screens, onChange)
            }
            if (canMoveUp) ActionText("▲", onMoveUp)
            if (canMoveDown) ActionText("▼", onMoveDown)
            ActionText("✕", onRemove)
        }
        // Value: only the kinds that carry one (Set / Adjust). Toggle/Navigate have no value.
        if (action.kind.isEditable) {
            val scalar = valueScalarType(action, fields)
            if (scalar != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp, start = 100.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (action is Action.Adjust) "by" else "to",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(28.dp),
                    )
                    ValueControl(scalarValueOf(action), scalar) { onChange(withScalarValue(action, it)) }
                }
            }
        }
    }
}

@Composable
private fun TargetPicker(
    action: Action,
    fields: List<viewforge.model.StateField>,
    screens: List<Screen>,
    onChange: (Action) -> Unit,
) {
    val isNav = action is Action.Navigate
    val options: List<Pair<String, String>> =
        if (isNav) {
            screens.map { it.name to it.id }
        } else {
            targetsFor(action.kind, fields, screens.map { it.id }).map { it to it }
        }
    val current = if (isNav) {
        screens.firstOrNull { it.id == (action as Action.Navigate).screenId }?.name ?: "—"
    } else {
        (action.targetLabel()) ?: "—"
    }
    Picker(current, options) { target -> onChange(retarget(action, target, fields)) }
}

/** The current target name of a non-navigation action, for the picker's display. */
private fun Action.targetLabel(): String? = when (this) {
    is Action.SetState -> target
    is Action.Toggle -> target
    is Action.Adjust -> target
    is Action.AppendRow -> target
    is Action.RemoveRow -> target
    is Action.Navigate -> null
}

/** A typed literal value control: a Switch for Bool, else a validated text field committing a typed [JsonPrimitive]. */
@Composable
private fun ValueControl(current: JsonPrimitive?, scalar: ScalarType, onChange: (JsonPrimitive) -> Unit) {
    if (scalar == ScalarType.BOOL) {
        Switch(
            checked = current?.content?.toBooleanStrictOrNull() ?: false,
            onCheckedChange = { onChange(JsonPrimitive(it)) },
        )
        return
    }
    var text by remember(current) { mutableStateOf(current?.content ?: "") }
    var invalid by remember(current) { mutableStateOf(false) }
    Box(Modifier.width(120.dp)) {
        FieldBox(error = invalid) {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    val parsed = parseValue(it, scalar)
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
}

/** Parse [text] as [scalar], or null when it doesn't (so an invalid number is withheld, never committed). */
private fun parseValue(text: String, scalar: ScalarType): JsonPrimitive? = when (scalar) {
    ScalarType.STRING -> JsonPrimitive(text)
    ScalarType.INT -> text.toIntOrNull()?.let { JsonPrimitive(it) }
    ScalarType.FLOAT -> text.toFloatOrNull()?.let { JsonPrimitive(it) }
    ScalarType.BOOL -> text.toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
}

/** A compact click-to-open dropdown picker: shows [display], offers [options] (label to value), commits [onPick]. */
@Composable
private fun <T> Picker(display: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FieldBox(onClick = { open = true }) {
            Text(display, style = fieldStyle(), modifier = Modifier.fillMaxWidth())
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onPick(value)
                        open = false
                    },
                )
            }
        }
    }
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> = toMutableList().apply { this[index] = value }

private fun <T> List<T>.swap(a: Int, b: Int): List<T> = toMutableList().apply {
    val t = this[a]
    this[a] = this[b]
    this[b] =
        t
}

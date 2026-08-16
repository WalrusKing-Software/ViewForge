@file:OptIn(ExperimentalFoundationApi::class)

package viewforge.editor.panels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import viewforge.editor.state.EditorState
import viewforge.model.ModifierEntry
import viewforge.model.Node
import viewforge.model.Parameter
import viewforge.model.ParameterType
import viewforge.model.PropDefinition
import viewforge.model.PropValue
import viewforge.model.Theme
import viewforge.model.UserComponent

/**
 * The property inspector — an **editing** surface from M5 (FEATURES I1–I5). It is entirely
 * data-driven from the framework package's schema (`catalog.propsFor` / `catalog.modifierCatalog`):
 * there is **no per-component code** here, so a new component gets typed editors for free (CLAUDE.md
 * anti-patterns).
 *
 * Every edit runs a command through [EditorState] (setProp / modifier ops), so all of it is undoable
 * and the canvas updates live (the document is Compose state). Continuous edits coalesce into one undo
 * step (D3). Modifiers are shown and reordered as an **ordered list** because order is semantic
 * (ADR-005).
 */
@Composable
fun Inspector(state: EditorState, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        PanelHeader("Inspector")
        val node = state.selectedNode
        if (node == null) MutedText("Select a node to inspect") else InspectorBody(state, node)
    }
}

@Composable
private fun InspectorBody(state: EditorState, node: Node) {
    val theme = state.document.theme
    PanelColumn(Modifier.padding(bottom = 12.dp)) {
        Text(displayLabel(node), style = MaterialTheme.typography.titleSmall)
        Text(node.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionLabel("Identity")
        KeyValueRow("id", node.id.value)
        node.name?.let { KeyValueRow("name", it) }
        if (node.locked) KeyValueRow("locked", "true")
        if (node.hidden) KeyValueRow("hidden", "true")

        // A user-component instance edits its referenced component's parameters, not a fixed prop schema.
        if (node.type == UserComponent.TYPE) {
            SectionLabel("Parameters")
            InstanceParameters(state, node, theme)
        } else {
            SectionLabel("Props")
            // With several same-type nodes selected, a prop edit applies to all of them (C10).
            val shared = state.sameTypeSelection().size
            if (shared > 1) {
                Text(
                    "Editing $shared ${shortTypeName(node.type)} — changes apply to all",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            val defs = state.catalog.propsFor(node.type)
            val known = defs.map { it.name }.toSet()
            if (defs.isEmpty() && node.props.isEmpty()) MutedText("none")
            defs.forEach { def -> PropRow(state, node, def, theme) }
            // Any props the schema doesn't describe are shown read-only rather than hidden.
            node.props.filterKeys { it !in known }.forEach { (k, v) -> KeyValueRow(k, formatPropValue(v)) }
        }

        SectionLabel("Modifiers (order matters)")
        ModifierEditor(state, node, theme)
    }
}

// --- props ---------------------------------------------------------------------------------------

@Composable
private fun PropRow(state: EditorState, node: Node, def: PropDefinition, theme: Theme) {
    val value = node.props[def.name]
    // A prop bound to a component parameter (ADR-028) shows a read-only chip, not the literal controls.
    val isParam = value is PropValue.ParamRef
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                def.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!isParam) {
                // Reset to default (I7) only when the prop is set to something other than its default.
                // Like the value edit, this fans out to every same-type selected node (C10).
                if (value != null && value != def.default) {
                    ActionText("reset") { state.setPropShared(def.name, def.default) }
                }
                // Escape hatch (I6): retype this prop as a raw Kotlin expression. Primary-only — an
                // expression is derived from this node's current value, so it isn't a shared edit.
                if (value !is PropValue.RawExpression) {
                    ActionText("ƒx") { state.setProp(node.id, def.name, expressionValue(value.literalText() ?: "")) }
                }
                // Promote to a component parameter (ADR-028) while editing the component in place.
                if (state.canPromoteToParameter(node, def)) {
                    ActionText("⇧ param") { state.promotePropToParameter(node.id, def.name) }
                }
            }
        }
        ValueControl(
            type = def.type,
            value = value ?: def.default,
            theme = theme,
            // Editing a prop applies to every same-type selected node (C10 shared edit); with a lone
            // selection this is just the primary.
            onChange = { state.setPropShared(def.name, it) },
            enumValues = def.enumValues,
            range = def.range,
            themeable = def.themeable,
            assets = state.document.assets,
        )
    }
}

// --- component instance arguments ----------------------------------------------------------------

/**
 * The inspector surface for a `vforge.userComponent` instance: the referenced component's name and an
 * editable control per parameter, bound to the instance's argument value (a prop keyed by parameter
 * name, ADR-028). Editing sets or clears that prop through the same [EditorState.setProp] path as any
 * prop, so it is undoable and the canvas/codegen update live. An unset argument shows the parameter's
 * default; "clear" removes an explicit argument so the default applies again.
 */
@Composable
private fun InstanceParameters(state: EditorState, node: Node, theme: Theme) {
    val component = state.componentOfInstance(node)
    if (component == null) {
        MutedText("unresolved component")
        return
    }
    KeyValueRow("component", component.name)
    if (component.parameters.isEmpty()) {
        MutedText("no parameters")
        return
    }
    component.parameters.forEach { param -> ArgRow(state, node, param, theme) }
}

@Composable
private fun ArgRow(state: EditorState, node: Node, param: Parameter, theme: Theme) {
    val value = node.props[param.name]
    val def = ParameterType.propDefinition(param)
    if (def == null) {
        // A parameter of a type the inspector can't edit (should not arise) is shown read-only.
        KeyValueRow(param.name, (value ?: param.default)?.let { formatPropValue(it) } ?: "(unset)")
        return
    }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                param.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // Clear an explicit argument so the parameter's own default applies again.
            if (value != null) ActionText("clear") { state.setProp(node.id, param.name, null) }
        }
        ValueControl(
            type = def.type,
            value = value ?: def.default,
            theme = theme,
            onChange = { state.setProp(node.id, param.name, it) },
            themeable = def.themeable,
            assets = state.document.assets,
        )
    }
}

// --- modifiers -----------------------------------------------------------------------------------

@Composable
private fun ModifierEditor(state: EditorState, node: Node, theme: Theme) {
    AddModifierMenu(state, node)
    if (node.modifiers.isEmpty()) {
        MutedText("none")
        return
    }
    val drag = remember(node.id) { ModifierDrag() }
    drag.orderedIds = node.modifiers.map { it.id }

    node.modifiers.forEachIndexed { index, entry ->
        DropLine(active = drag.draggingId != null && drag.dropIndex == index)
        ModifierCard(state, node, entry, index, drag, theme)
    }
    DropLine(active = drag.draggingId != null && drag.dropIndex == node.modifiers.size)
}

@Composable
private fun ModifierCard(
    state: EditorState,
    node: Node,
    entry: ModifierEntry,
    index: Int,
    drag: ModifierDrag,
    theme: Theme,
) {
    val key = entry.id
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(6.dp)
            .alpha(if (drag.draggingId == entry.id) 0.4f else 1f)
            .onGloballyPositioned {
                drag.bounds[key] = it.boundsInWindow()
                drag.coords[key] = it
            },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Drag handle — the one place the reorder gesture lives, so args stay editable.
            Text(
                "⠿",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.pointerInput(entry.id) {
                    detectDragGestures(
                        onDragStart = { drag.begin(entry.id) },
                        onDrag = { change, _ ->
                            change.consume()
                            drag.coords[key]?.let { c -> drag.update(c.localToWindow(change.position).y) }
                        },
                        onDragEnd = { drag.commit { from, to -> state.moveModifier(node.id, from, to) } },
                        onDragCancel = { drag.reset() },
                    )
                },
            )
            EnableToggle(entry.enabled) { state.toggleModifier(node.id, entry.id) }
            Text(
                shortTypeName(entry.type),
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            ActionText("✕") { state.removeModifier(node.id, entry.id) }
        }
        val def = state.catalog.modifierDef(entry.type)
        def?.args?.forEach { arg ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    arg.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.9f),
                )
                Box(Modifier.weight(1.6f)) {
                    ValueControl(
                        type = arg.type,
                        value = entry.args[arg.name] ?: arg.default,
                        theme = theme,
                        onChange = { state.setModifierArg(node.id, entry.id, arg.name, it) },
                        range = arg.range,
                        themeable = arg.themeable,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddModifierMenu(state: EditorState, node: Node) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(vertical = 2.dp)) {
        ActionText("+ add modifier") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Scope-aware: the catalog withholds modifiers this node's parent can't host (e.g. weight
            // outside a Row/Column, #158), so the menu never offers one the canvas would ignore.
            state.catalog.availableModifiers(state.parentType(node.id)).forEach { def ->
                DropdownMenuItem(
                    text = { Text(def.label, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        state.addModifier(node.id, def.type)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EnableToggle(enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = if (enabled) "☑" else "☐",
        style = MaterialTheme.typography.bodySmall,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun DropLine(active: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
    )
}

@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp),
    )
}

/**
 * Transient state for dragging a modifier row to a new position. Single-level, so the drop index is
 * just "how many rows sit above the pointer"; on release it becomes a [EditorState.moveModifier] call.
 */
private class ModifierDrag {
    var draggingId by mutableStateOf<String?>(null)
        private set
    var dropIndex by mutableStateOf(-1)
        private set

    val bounds = mutableStateMapOf<String, Rect>()
    val coords = HashMap<String, LayoutCoordinates>()
    var orderedIds: List<String> = emptyList()

    fun begin(id: String) {
        draggingId = id
    }

    fun update(windowY: Float) {
        var ins = 0
        for (id in orderedIds) {
            val r = bounds[id] ?: continue
            if (windowY > (r.top + r.bottom) / 2f) ins++
        }
        dropIndex = ins
    }

    fun commit(onMove: (from: Int, to: Int) -> Unit) {
        val id = draggingId
        val ins = dropIndex
        if (id != null && ins >= 0) {
            val from = orderedIds.indexOf(id)
            if (from >= 0) {
                val to = (if (ins > from) ins - 1 else ins).coerceIn(0, orderedIds.lastIndex)
                if (to != from) onMove(from, to)
            }
        }
        reset()
    }

    fun reset() {
        draggingId = null
        dropIndex = -1
    }
}

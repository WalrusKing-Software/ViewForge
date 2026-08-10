package viewforge.editor.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import viewforge.editor.state.EditorState
import viewforge.model.Node

/**
 * The property inspector, read-only at M3 (FEATURES I1 preview): it displays the selected node's
 * type, name, props, and — crucially — its modifiers **in order**, since order is semantic
 * (TECHNICAL_NOTES §1). Typed, editable controls generated from `PropDefinition` arrive at M5; this
 * milestone proves selection reaches the inspector and shows the node faithfully first.
 *
 * There is deliberately **no per-component code** here (CLAUDE.md anti-patterns) — it walks whatever
 * props and modifiers the node carries, so a new component needs no inspector change.
 */
@Composable
fun Inspector(state: EditorState, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        PanelHeader("Inspector")
        val node = state.selectedNode
        if (node == null) {
            MutedText("Select a node to inspect")
        } else {
            InspectorBody(node)
        }
    }
}

@Composable
private fun InspectorBody(node: Node) {
    PanelColumn(Modifier.padding(bottom = 12.dp)) {
        Text(displayLabel(node), style = MaterialTheme.typography.titleSmall)
        Text(
            text = node.type,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("Identity")
        KeyValueRow("id", node.id.value)
        node.name?.let { KeyValueRow("name", it) }
        if (node.locked) KeyValueRow("locked", "true")
        if (node.hidden) KeyValueRow("hidden", "true")

        SectionLabel("Props")
        if (node.props.isEmpty()) {
            MutedText("none")
        } else {
            node.props.forEach { (key, value) -> KeyValueRow(key, formatPropValue(value)) }
        }

        SectionLabel("Modifiers (order matters)")
        if (node.modifiers.isEmpty()) {
            MutedText("none")
        } else {
            // Numbered so the semantic chain order is unmistakable, top-to-bottom (TECHNICAL_NOTES §1).
            node.modifiers.forEachIndexed { index, entry -> KeyValueRow("${index + 1}.", formatModifier(entry)) }
        }

        if (node.slots.isNotEmpty()) {
            SectionLabel("Slots")
            node.slots.forEach { (name, children) ->
                KeyValueRow(name, "${children.size} ${if (children.size == 1) "child" else "children"}")
            }
        }
    }
}

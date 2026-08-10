package viewforge.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import viewforge.editor.state.EditorState
import viewforge.model.Node
import viewforge.model.allChildren

/**
 * The layers/tree panel (FEATURES T1): a hierarchical view of the document that mirrors the IR
 * exactly and stays in sync with the canvas — clicking a row selects that node, and the row for the
 * canvas-selected node is highlighted. It is also the *complete* selection surface for nodes the
 * canvas can't reach (zero-size or hidden — TECHNICAL_NOTES §3), which is why it is always present.
 *
 * Reading and writing selection both go through [EditorState], so canvas and tree can never disagree.
 */
@Composable
fun TreePanel(state: EditorState, modifier: Modifier = Modifier) {
    // Per-node expand/collapse, defaulting to expanded. Keyed by node id so it survives recomposition.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    Column(modifier.verticalScroll(rememberScrollState())) {
        PanelHeader("Layers")
        val root = state.activeScreen?.root
        if (root == null) {
            MutedText("No screen")
        } else {
            NodeRows(root, depth = 0, state = state, expanded = expanded)
        }
    }
}

@Composable
private fun NodeRows(node: Node, depth: Int, state: EditorState, expanded: MutableMap<String, Boolean>) {
    val hasChildren = node.allChildren().isNotEmpty()
    val isExpanded = expanded[node.id.value] ?: true
    NodeRow(
        node = node,
        depth = depth,
        hasChildren = hasChildren,
        expanded = isExpanded,
        selected = state.selectedId == node.id,
        onToggle = { expanded[node.id.value] = !isExpanded },
        onSelect = { state.select(node.id) },
    )
    if (hasChildren && isExpanded) {
        node.children.forEach { NodeRows(it, depth + 1, state, expanded) }
        // Slot children are grouped under a non-selectable slot header so the region is legible.
        node.slots.forEach { (slotName, list) ->
            SlotHeader(slotName, depth + 1)
            list.forEach { NodeRows(it, depth + 2, state, expanded) }
        }
    }
}

@Composable
private fun NodeRow(
    node: Node,
    depth: Int,
    hasChildren: Boolean,
    expanded: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onSelect)
            .padding(start = (8 + depth * INDENT).dp, top = 3.dp, bottom = 3.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Disclosure triangle (or a same-width spacer for leaves, so labels line up).
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (hasChildren) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable(onClick = onToggle),
                )
            }
        }
        Text(
            text = displayLabel(node),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color =
            when {
                selected -> MaterialTheme.colorScheme.onPrimaryContainer
                node.hidden -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun SlotHeader(name: String, depth: Int) {
    Text(
        text = "$name:",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = (8 + depth * INDENT).dp, top = 3.dp, bottom = 3.dp),
    )
}

private const val INDENT = 14

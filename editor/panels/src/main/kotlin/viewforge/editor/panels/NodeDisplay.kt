package viewforge.editor.panels

import viewforge.model.ModifierEntry
import viewforge.model.Node
import viewforge.model.PropValue

/**
 * Pure presentation helpers shared by the tree panel and the read-only inspector (M3). Kept
 * Compose-free so the formatting is unit-testable without a UI harness, and so both panels render a
 * node identically. None of this parses or evaluates a value — a [PropValue.RawExpression] is shown
 * verbatim and flagged, never executed (CLAUDE.md rule 8, PF-4).
 */

/** The last segment of a fully-qualified type, e.g. `compose.material3.Button` → `Button`. */
internal fun shortTypeName(type: String): String = type.substringAfterLast('.')

/** What a node is called in the tree/inspector: its user-given [Node.name], else its short type. */
internal fun displayLabel(node: Node): String = node.name ?: shortTypeName(node.type)

/** A one-line, read-only rendering of a typed prop value (DATA_MODEL §6). Never evaluated. */
internal fun formatPropValue(value: PropValue): String = when (value) {
    is PropValue.Literal -> value.value.content
    is PropValue.ThemeRef -> "→ theme: ${value.token}"
    is PropValue.ResourceRef -> "→ resource: ${value.assetId}"
    is PropValue.RawExpression -> "${value.code}  (unverified)"
    is PropValue.StateBinding -> "→ binding: ${value.path}"
}

/** A one-line summary of a modifier entry, preserving its args and disabled state (order is shown by position). */
internal fun formatModifier(entry: ModifierEntry): String {
    val name = shortTypeName(entry.type)
    val args = entry.args.entries.joinToString(", ") { (k, v) -> "$k=${formatPropValue(v)}" }
    val call = if (args.isEmpty()) name else "$name($args)"
    return if (entry.enabled) call else "$call — disabled"
}

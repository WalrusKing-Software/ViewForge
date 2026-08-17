package viewforge.editor.panels

/**
 * Pure keyboard-navigation decisions for the layers tree (T5, #101), kept out of the composable so they
 * are unit-testable. The panel supplies the visible state (order, whether a row has children and is
 * expanded) and applies the resulting action to selection / the expand map.
 */

/** What Left/Right does to a focused tree row, decided purely from whether it has children and is expanded. */
internal enum class TreeKeyAction { Expand, Collapse, ToParent, ToFirstChild, None }

/**
 * The Left/Right outcome for a row: Right expands a collapsed container else steps into its first child;
 * Left collapses an expanded container else steps out to the parent. A leaf ignores Right and steps to its
 * parent on Left.
 */
internal fun horizontalTreeAction(hasChildren: Boolean, expanded: Boolean, right: Boolean): TreeKeyAction = if (right) {
    when {
        hasChildren && !expanded -> TreeKeyAction.Expand
        hasChildren -> TreeKeyAction.ToFirstChild
        else -> TreeKeyAction.None
    }
} else {
    when {
        hasChildren && expanded -> TreeKeyAction.Collapse
        else -> TreeKeyAction.ToParent
    }
}

/**
 * The id Up/Down should move to along the navigable [order] from [current], clamped at the ends (so Up on
 * the first row and Down on the last stay put). With no current selection, Down lands on the first row and
 * Up on the last. Null only when [order] is empty.
 */
internal fun nextNavigable(order: List<String>, current: String?, delta: Int): String? {
    if (order.isEmpty()) return null
    val i = order.indexOf(current)
    if (i < 0) return if (delta >= 0) order.first() else order.last()
    return order[(i + delta).coerceIn(0, order.lastIndex)]
}

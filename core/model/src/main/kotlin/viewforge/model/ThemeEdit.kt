package viewforge.model

/**
 * Pure edits over the theme and over the [PropValue.ThemeRef]s that point at it (DATA_MODEL §8).
 * Stdlib-only and Compose-free, so the command layer and the editor share one authoritative set of
 * transforms. Like [NodeEdit], every operation is **identity-preserving**: a node, list, or map with
 * no changed reference is returned by reference, so a rename rebuilds only the paths that actually
 * referenced the renamed token.
 */

/** The four token groups a [PropValue.ThemeRef] can address; the prefix of a token ("colors.primary"). */
object ThemeCategory {
    const val COLORS = "colors"
    const val TYPOGRAPHY = "typography"
    const val SHAPES = "shapes"
    const val SPACING = "spacing"
}

/**
 * Whether [from] can be renamed to [to] within [category]: [from] must exist, [to] must not (a rename
 * never merges or clobbers another token), and the two must differ. Evaluated against this exact
 * theme so a command can mirror the check in `invert` against the pre-image.
 */
fun Theme.canRenameToken(category: String, from: String, to: String): Boolean {
    if (from == to || to.isBlank()) return false
    val keys = tokenKeys(category) ?: return false
    return from in keys && to !in keys
}

/**
 * This theme with token [from] renamed to [to] in [category], preserving map order, or null when the
 * rename is not valid ([canRenameToken] is false). Only the theme map is touched — rewriting the
 * [PropValue.ThemeRef]s that reference the token is [Node.mapThemeRefs]'s job.
 */
fun Theme.renameToken(category: String, from: String, to: String): Theme? {
    if (!canRenameToken(category, from, to)) return null
    return when (category) {
        ThemeCategory.COLORS -> copy(colors = colors.renameKey(from, to))
        ThemeCategory.TYPOGRAPHY -> copy(typography = typography.renameKey(from, to))
        ThemeCategory.SHAPES -> copy(shapes = shapes.renameKey(from, to))
        ThemeCategory.SPACING -> copy(spacing = spacing.renameKey(from, to))
        else -> null
    }
}

private fun Theme.tokenKeys(category: String): Set<String>? = when (category) {
    ThemeCategory.COLORS -> colors.keys
    ThemeCategory.TYPOGRAPHY -> typography.keys
    ThemeCategory.SHAPES -> shapes.keys
    ThemeCategory.SPACING -> spacing.keys
    else -> null
}

/** A copy of this map with the single key [from] renamed to [to], keeping insertion order. */
private fun <V> Map<String, V>.renameKey(from: String, to: String): Map<String, V> {
    val result = LinkedHashMap<String, V>(size)
    for ((k, v) in this) result[if (k == from) to else k] = v
    return result
}

/**
 * A copy of this subtree with every [PropValue.ThemeRef] token rewritten through [transform] — the
 * mechanism behind theme-token rename propagation (H5, DATA_MODEL §8: "a token rename can be
 * propagated automatically"). [transform] maps a token string (e.g. "colors.primary") to its
 * replacement; returning the same string leaves that reference untouched. References live in both
 * [Node.props] and each [ModifierEntry.args].
 */
fun Node.mapThemeRefs(transform: (String) -> String): Node {
    val newProps = props.mapThemeRefs(transform)
    val newModifiers = modifiers.mapThemeRefArgs(transform)
    val newChildren = children.mapThemeRefsEach(transform)
    val newSlots = slots.mapThemeRefsSlots(transform)
    return if (
        newProps === props &&
        newModifiers === modifiers &&
        newChildren === children &&
        newSlots === slots
    ) {
        this
    } else {
        copy(props = newProps, modifiers = newModifiers, children = newChildren, slots = newSlots)
    }
}

private fun PropValue.mapThemeRef(transform: (String) -> String): PropValue {
    if (this !is PropValue.ThemeRef) return this
    val next = transform(token)
    return if (next == token) this else PropValue.ThemeRef(next)
}

private fun Map<String, PropValue>.mapThemeRefs(transform: (String) -> String): Map<String, PropValue> {
    var changed = false
    val result = LinkedHashMap<String, PropValue>(size)
    for ((k, v) in this) {
        val next = v.mapThemeRef(transform)
        if (next !== v) changed = true
        result[k] = next
    }
    return if (changed) result else this
}

private fun List<ModifierEntry>.mapThemeRefArgs(transform: (String) -> String): List<ModifierEntry> {
    var changed = false
    val result = map { entry ->
        val nextArgs = entry.args.mapThemeRefs(transform)
        if (nextArgs === entry.args) {
            entry
        } else {
            changed = true
            entry.copy(args = nextArgs)
        }
    }
    return if (changed) result else this
}

private fun List<Node>.mapThemeRefsEach(transform: (String) -> String): List<Node> {
    var changed = false
    val result = map { child ->
        val next = child.mapThemeRefs(transform)
        if (next !== child) changed = true
        next
    }
    return if (changed) result else this
}

private fun Map<String, List<Node>>.mapThemeRefsSlots(transform: (String) -> String): Map<String, List<Node>> {
    var changed = false
    val result = LinkedHashMap<String, List<Node>>(size)
    for ((name, list) in this) {
        val next = list.mapThemeRefsEach(transform)
        if (next !== list) changed = true
        result[name] = next
    }
    return if (changed) result else this
}

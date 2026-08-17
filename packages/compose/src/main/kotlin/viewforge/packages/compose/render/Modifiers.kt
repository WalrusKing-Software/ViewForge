package viewforge.packages.compose.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import viewforge.model.ModifierEntry

/**
 * Folds a node's ordered [ModifierEntry] list into a single Compose [Modifier] (ARCHITECTURE §4.3).
 *
 * Order is semantic and is applied exactly as stored — `padding` then `background` differs from the
 * reverse (TECHNICAL_NOTES §1, ADR-005). Disabled entries are skipped in both render and codegen
 * (DATA_MODEL §7); unknown modifier types are skipped rather than failing the whole node, since the
 * Phase-1 allowlist is intentionally partial and the rest arrive at M5/M6.
 */
internal fun buildModifier(entries: List<ModifierEntry>, ctx: RenderContext): Modifier {
    var modifier: Modifier = Modifier
    for (entry in entries) {
        if (!entry.enabled) continue
        modifier = applyModifier(modifier, entry, ctx)
    }
    return modifier
}

private fun applyModifier(base: Modifier, entry: ModifierEntry, ctx: RenderContext): Modifier = when (entry.type) {
    "compose.fillMaxSize" -> base.fillMaxSize()
    "compose.fillMaxWidth" -> base.fillMaxWidth()
    "compose.fillMaxHeight" -> base.fillMaxHeight()

    "compose.padding" -> {
        val p = paddingSpec(entry.args)
        base.padding(PaddingValues(start = p.start.dp, top = p.top.dp, end = p.end.dp, bottom = p.bottom.dp))
    }

    "compose.size" -> {
        val s = sizeSpec(entry.args)
        when {
            s.width != null && s.height != null -> base.size(s.width.dp, s.height.dp)
            s.width != null -> base.width(s.width.dp)
            s.height != null -> base.height(s.height.dp)
            else -> base
        }
    }
    "compose.width" -> singleDimen(entry.args, "width")?.let { base.width(it.dp) } ?: base
    "compose.height" -> singleDimen(entry.args, "height")?.let { base.height(it.dp) } ?: base

    "compose.background" ->
        colorArgb(entry.args["color"], ctx.theme, ctx.dark)?.let { base.background(Color(it)) } ?: base

    // weight needs the parent's RowScope/ColumnScope, carried in ctx.weightApplier (#158). Absent (any
    // other parent) or non-positive → no-op, matching codegen which drops weight outside a Row/Column.
    "compose.weight" -> {
        val w = entry.args["weight"].literalFloat()
        if (ctx.weightApplier != null && w != null && w > 0f) ctx.weightApplier.invoke(base, w) else base
    }

    else -> base // outside the M2 allowlist — passed through untouched, not fatal
}

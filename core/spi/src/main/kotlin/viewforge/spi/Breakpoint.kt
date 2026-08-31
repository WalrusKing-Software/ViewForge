package viewforge.spi

/**
 * A responsive breakpoint (ADR-030/037, #314): a model breakpoint [id] (opaque to `core/model`) mapped to
 * the minimum viewport width in dp at which it applies, plus a display [label] for the inspector/canvas.
 * Framework-neutral pure data — no Compose types — so it lives in `core/spi` alongside [PreviewProfile], and
 * each [TargetDefinition] supplies its own set: the Compose Android target maps the ids to Material **window
 * size classes**. The base (`compact`, below the smallest threshold) is *not* an entry — it is the default
 * `props` and the `else` branch — so a breakpoint set carries only the non-base thresholds.
 *
 * Codegen branches on [minWidthDp] (`BoxWithConstraints { maxWidth >= N.dp }`, ADR-037) and the canvas
 * resolves the active breakpoint against the *same* thresholds via [breakpointForWidth], so the preview and
 * the generated code can never disagree about which breakpoint a width falls into.
 */
data class Breakpoint(val id: String, val label: String, val minWidthDp: Int) {
    companion object {
        /** The display label for the base (below-smallest-threshold) case, which has no [Breakpoint] entry. */
        const val BASE_LABEL: String = "Compact"
    }
}

/**
 * The breakpoint id active at a viewport [widthDp]: the largest [breakpoints] entry whose [minWidthDp] the
 * width meets, or `null` for the base/`compact` case below the smallest threshold. Pure and width-based (not
 * platform-based), the render-time twin of codegen's largest-first `BoxWithConstraints` branching — both read
 * a real width against the same thresholds, so a wide desktop frame resolves to the same breakpoint an Android
 * tablet would.
 */
fun breakpointForWidth(widthDp: Float, breakpoints: List<Breakpoint>): String? =
    breakpoints.filter { widthDp >= it.minWidthDp }.maxByOrNull { it.minWidthDp }?.id

/** The display label for the breakpoint [id] active on a frame — a [breakpoints] entry's label, else base. */
fun breakpointLabel(id: String?, breakpoints: List<Breakpoint>): String =
    breakpoints.firstOrNull { it.id == id }?.label ?: Breakpoint.BASE_LABEL

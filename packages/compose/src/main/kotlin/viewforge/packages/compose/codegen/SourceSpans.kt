package viewforge.packages.compose.codegen

/**
 * Generated source paired with a node→source-range map (G3, #51). [code] is byte-for-byte the normal
 * generated text; [spans] maps a node id to the half-open character range in [code] that the node's
 * code occupies, so the live preview can scroll to and highlight the selected node.
 *
 * The map is a read-only side-channel: it never alters [code]. It is produced by an instrumented pass
 * (see [SourceSpans]) that the export path never runs, so codegen output is unaffected.
 */
data class GeneratedSource(val code: String, val spans: Map<String, IntRange>)

/**
 * The span side-channel (G3, #51). Because KotlinPoet renders a whole `FileSpec` to an opaque string
 * with no per-node offsets, the emitter — when recording spans — brackets each node's emitted code with
 * unique **comment lines** carrying the node id. [strip] then removes those marker lines and, in the
 * process, records where each node's real code lands in the cleaned text.
 *
 * Markers are whole lines placed at statement boundaries, so removing them restores the exact
 * un-instrumented output; an invariant test (`clean == generateScreen(...)`) guards that for every
 * fixture, turning any KotlinPoet-formatting surprise into a loud test failure rather than altered code.
 */
object SourceSpans {
    /** A distinctive comment prefix that cannot collide with generated code or a node id (ULIDs are alnum). */
    private const val OPEN = "//__VFSPAN__O__"
    private const val CLOSE = "//__VFSPAN__C__"

    /** The opening marker line for node [id] (emitted before the node's code). */
    fun open(id: String): String = OPEN + id

    /** The closing marker line for node [id] (emitted after the node's code). */
    fun close(id: String): String = CLOSE + id

    /**
     * Split [instrumented] (a rendered file carrying [open]/[close] marker lines) into the clean source
     * and the node→range map. A range runs from the start of the node's first real line to just past its
     * last, so highlighting it covers exactly the node's emitted lines. Markers nest properly (a child is
     * emitted inside its parent's brackets), so a plain per-id start/end record suffices — no stack.
     */
    fun strip(instrumented: String): GeneratedSource {
        val clean = StringBuilder()
        val starts = HashMap<String, Int>()
        val ends = HashMap<String, Int>()
        var i = 0
        while (i < instrumented.length) {
            val nl = instrumented.indexOf('\n', i)
            val lineEnd = if (nl == -1) instrumented.length else nl
            val line = instrumented.substring(i, lineEnd)
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith(OPEN) -> starts[trimmed.substring(OPEN.length)] = clean.length
                trimmed.startsWith(CLOSE) -> ends[trimmed.substring(CLOSE.length)] = clean.length
                else -> {
                    clean.append(line)
                    if (nl != -1) clean.append('\n')
                }
            }
            if (nl == -1) break
            i = nl + 1
        }
        val spans = starts.mapNotNull { (id, start) -> ends[id]?.let { end -> id to (start until end) } }.toMap()
        return GeneratedSource(clean.toString(), spans)
    }
}

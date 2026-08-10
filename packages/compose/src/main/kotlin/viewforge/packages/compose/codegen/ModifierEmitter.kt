package viewforge.packages.compose.codegen

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.joinToCode
import viewforge.model.ModifierEntry
import viewforge.model.Theme
import viewforge.packages.compose.render.paddingSpec
import viewforge.packages.compose.render.singleDimen
import viewforge.packages.compose.render.sizeSpec

/**
 * Emits a node's ordered [ModifierEntry] chain as a KotlinPoet [CodeBlock], mirroring
 * `render/Modifiers.kt` fold-for-fold so the drawn and generated chains match (TECHNICAL_NOTES §2).
 *
 * **Order is emitted exactly as stored** — semantic and non-commutative (TECHNICAL_NOTES §1,
 * CLAUDE.md rule 2). Disabled entries are skipped in both render and codegen (DATA_MODEL §7). The
 * receiver differs by position: the root chains onto the `modifier` parameter (so the caller's
 * modifier applies first, DATA_MODEL §12.1), every other node onto a fresh `Modifier`.
 */
internal object ModifierEmitter {
    /** The root node's chain: `modifier` (the composable's param) plus its own ordered chain. */
    fun rootChain(entries: List<ModifierEntry>, theme: Theme): CodeBlock {
        val b = CodeBlock.builder().add("modifier")
        fragments(entries, theme).forEach { b.add(it) }
        return b.build()
    }

    /** A non-root node's chain onto a fresh `Modifier`, or null when it has no enabled modifiers. */
    fun nodeChain(entries: List<ModifierEntry>, theme: Theme): CodeBlock? {
        val frags = fragments(entries, theme)
        if (frags.isEmpty()) return null
        val b = CodeBlock.builder().add("%T", ComposeNames.Modifier)
        frags.forEach { b.add(it) }
        return b.build()
    }

    private fun fragments(entries: List<ModifierEntry>, theme: Theme): List<CodeBlock> =
        entries.filter { it.enabled }.mapNotNull { fragment(it, theme) }

    /** One `.call(...)` fragment, or null for a modifier that resolves to no-op (mirrors the renderer). */
    private fun fragment(entry: ModifierEntry, theme: Theme): CodeBlock? = when (entry.type) {
        "compose.fillMaxSize" -> CodeBlock.of(".%M()", ComposeNames.fillMaxSize)
        "compose.fillMaxWidth" -> CodeBlock.of(".%M()", ComposeNames.fillMaxWidth)
        "compose.fillMaxHeight" -> CodeBlock.of(".%M()", ComposeNames.fillMaxHeight)
        "compose.padding" -> paddingFragment(entry)
        "compose.size" -> sizeFragment(entry)
        "compose.width" -> singleDimen(entry.args, "width")?.let { dimenFragment(ComposeNames.width, it) }
        "compose.height" -> singleDimen(entry.args, "height")?.let { dimenFragment(ComposeNames.height, it) }
        "compose.background" ->
            entry.args["color"]?.let {
                CodeBlock.of(".%M(%L)", ComposeNames.background, CodegenValues.color(it, theme))
            }
        else -> throw CodegenException("Unsupported modifier '${entry.type}' (outside the Phase-1 allowlist)")
    }

    private fun dimenFragment(member: com.squareup.kotlinpoet.MemberName, value: Int): CodeBlock =
        CodeBlock.of(".%M(%L)", member, CodegenValues.dp(value))

    /** Idiomatic padding: uniform → `padding(24.dp)`; otherwise only the non-zero edges, in order. */
    private fun paddingFragment(entry: ModifierEntry): CodeBlock {
        val p = paddingSpec(entry.args)
        if (p.start == p.top && p.top == p.end && p.end == p.bottom) {
            return CodeBlock.of(".%M(%L)", ComposeNames.padding, CodegenValues.dp(p.start))
        }
        val edges = buildList {
            if (p.start != 0) add(CodeBlock.of("start = %L", CodegenValues.dp(p.start)))
            if (p.top != 0) add(CodeBlock.of("top = %L", CodegenValues.dp(p.top)))
            if (p.end != 0) add(CodeBlock.of("end = %L", CodegenValues.dp(p.end)))
            if (p.bottom != 0) add(CodeBlock.of("bottom = %L", CodegenValues.dp(p.bottom)))
        }
        return CodeBlock.of(".%M(%L)", ComposeNames.padding, edges.joinToCode(", "))
    }

    /** `size(N.dp)` when square, `size(width = .., height = ..)` when differing, else a single axis. */
    private fun sizeFragment(entry: ModifierEntry): CodeBlock? {
        val s = sizeSpec(entry.args)
        return when {
            s.width != null && s.height != null && s.width == s.height ->
                CodeBlock.of(".%M(%L)", ComposeNames.size, CodegenValues.dp(s.width))
            s.width != null && s.height != null ->
                CodeBlock.of(
                    ".%M(width = %L, height = %L)",
                    ComposeNames.size,
                    CodegenValues.dp(s.width),
                    CodegenValues.dp(s.height),
                )
            s.width != null -> dimenFragment(ComposeNames.width, s.width)
            s.height != null -> dimenFragment(ComposeNames.height, s.height)
            else -> null
        }
    }
}

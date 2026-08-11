package viewforge.packages.compose.codegen

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import viewforge.model.Asset
import viewforge.model.Node
import viewforge.model.Theme

/**
 * Emits a node subtree as a KotlinPoet [CodeBlock], mirroring `render/Components.kt` component for
 * component: the same supported set, with the same argument order as each renderer's Composable call,
 * so the drawn tree and generated tree are the same tree (TECHNICAL_NOTES §2).
 *
 * Each component owns its emitter here, so adding one is a local change beside its renderer — never a
 * change to the pipeline. An unsupported type fails loudly (CLAUDE.md: a visible error beats a silent
 * wrong render/emit); `hidden` nodes are dropped from output (DATA_MODEL §5).
 *
 * [assets] resolves an `Image`'s `ResourceRef` to its project-relative path for `painterResource`.
 */
internal class ComponentEmitter(private val theme: Theme, assets: List<Asset> = emptyList()) {
    private val assetsById: Map<String, Asset> = assets.associateBy { it.id }

    /** Emits [node]. [isRoot] chains its modifier onto the composable's `modifier` parameter. */
    fun emit(node: Node, isRoot: Boolean): CodeBlock {
        val mod = if (isRoot) {
            ModifierEmitter.rootChain(node.modifiers, theme)
        } else {
            ModifierEmitter.nodeChain(node.modifiers, theme)
        }
        return when (node.type) {
            "compose.foundation.layout.Column" -> layout(ComposeNames.Column, node, mod, columnArgs(node))
            "compose.foundation.layout.Row" -> layout(ComposeNames.Row, node, mod, rowArgs(node))
            "compose.foundation.layout.Box" -> layout(ComposeNames.Box, node, mod, boxArgs(node))
            "compose.foundation.layout.Spacer" -> call(ComposeNames.Spacer, modifierArg(mod), content = null)
            "compose.foundation.lazy.LazyColumn" -> lazyList(ComposeNames.LazyColumn, node, mod, columnArgs(node))
            "compose.foundation.lazy.LazyRow" -> lazyList(ComposeNames.LazyRow, node, mod, rowArgs(node))
            "compose.material3.Text" -> call(ComposeNames.Text, textArgs(node, mod), content = null)
            "compose.material3.Button" -> button(node, mod)
            "compose.material3.Card" -> layout(ComposeNames.Card, node, mod, emptyList())
            "compose.material3.Surface" -> layout(ComposeNames.Surface, node, mod, emptyList())
            "compose.material3.HorizontalDivider" -> call(
                ComposeNames.HorizontalDivider,
                dividerArgs(node, mod),
                content = null,
            )
            "compose.material3.Checkbox" -> call(ComposeNames.Checkbox, toggleArgs(node, mod), content = null)
            "compose.material3.Switch" -> call(ComposeNames.Switch, toggleArgs(node, mod), content = null)
            "compose.foundation.Image" -> call(ComposeNames.Image, imageArgs(node, mod), content = null)
            else -> throw CodegenException("Unsupported component '${node.type}'")
        }
    }

    // --- per-component argument lists (order mirrors the renderer's Composable call) --------------

    private fun columnArgs(node: Node): List<CodeBlock> = buildList {
        node.props["verticalArrangement"]?.let {
            add(named("verticalArrangement", CodegenValues.enum("verticalArrangement", it)))
        }
        node.props["horizontalAlignment"]?.let {
            add(named("horizontalAlignment", CodegenValues.enum("horizontalAlignment", it)))
        }
    }

    private fun rowArgs(node: Node): List<CodeBlock> = buildList {
        node.props["horizontalArrangement"]?.let {
            add(named("horizontalArrangement", CodegenValues.enum("horizontalArrangement", it)))
        }
        node.props["verticalAlignment"]?.let {
            add(named("verticalAlignment", CodegenValues.enum("verticalAlignment", it)))
        }
    }

    private fun boxArgs(node: Node): List<CodeBlock> = buildList {
        node.props["contentAlignment"]?.let {
            add(named("contentAlignment", CodegenValues.enum("contentAlignment", it)))
        }
    }

    private fun textArgs(node: Node, mod: CodeBlock?): List<CodeBlock> = buildList {
        add(named("text", CodegenValues.text(node.props["text"])))
        if (mod != null) add(named("modifier", mod))
        node.props["color"]?.let { add(named("color", CodegenValues.color(it, theme))) }
        node.props["style"]?.let { add(named("style", CodegenValues.typography(it, theme))) }
    }

    private fun imageArgs(node: Node, mod: CodeBlock?): List<CodeBlock> = buildList {
        add(named("painter", CodegenValues.painter(node.props["source"], assetsById)))
        add(named("contentDescription", CodegenValues.nullableString(node.props["contentDescription"])))
        if (mod != null) add(named("modifier", mod))
        node.props["contentScale"]?.let { add(named("contentScale", CodegenValues.enum("contentScale", it))) }
    }

    private fun dividerArgs(node: Node, mod: CodeBlock?): List<CodeBlock> = buildList {
        if (mod != null) add(named("modifier", mod))
        node.props["thickness"]?.let { add(named("thickness", CodegenValues.dpProp(it))) }
    }

    /** `Checkbox`/`Switch` share a signature: checked, onCheckedChange, modifier, enabled (order mirrors the renderer). */
    private fun toggleArgs(node: Node, mod: CodeBlock?): List<CodeBlock> = buildList {
        add(named("checked", CodegenValues.bool(node.props["checked"])))
        add(named("onCheckedChange", CodegenValues.lambda(node.props["onCheckedChange"])))
        if (mod != null) add(named("modifier", mod))
        node.props["enabled"]?.let { add(named("enabled", CodegenValues.bool(it))) }
    }

    // --- shape helpers ---------------------------------------------------------------------------

    /** A layout container: its own args, then children (hidden dropped) as the trailing lambda. */
    private fun layout(callee: MemberName, node: Node, mod: CodeBlock?, extra: List<CodeBlock>): CodeBlock =
        call(callee, modifierArg(mod) + extra, content = body(node.children))

    /**
     * A lazy list (`LazyColumn`/`LazyRow`): same args as its eager twin, but each static child is
     * wrapped in its own `item { … }` — the `LazyListScope` DSL, mirroring the renderer (DATA_MODEL
     * §12.2: static children only in Phase 1).
     */
    private fun lazyList(callee: MemberName, node: Node, mod: CodeBlock?, extra: List<CodeBlock>): CodeBlock =
        call(callee, modifierArg(mod) + extra, content = lazyBody(node.children))

    /** Children as `item { … }` entries; hidden nodes excluded from codegen (DATA_MODEL §5). */
    private fun lazyBody(children: List<Node>): CodeBlock {
        val b = CodeBlock.builder()
        children.filterNot { it.hidden }.forEach { child ->
            b.add("item {\n").indent().add("%L\n", emit(child, isRoot = false)).unindent().add("}\n")
        }
        return b.build()
    }

    private fun button(node: Node, mod: CodeBlock?): CodeBlock {
        val args = buildList {
            add(named("onClick", CodegenValues.lambda(node.props["onClick"])))
            if (mod != null) add(named("modifier", mod))
        }
        return call(ComposeNames.Button, args, content = body(node.slots["content"].orEmpty()))
    }

    /** The `modifier = <chain>` argument, or nothing when a non-root node has no modifiers. */
    private fun modifierArg(mod: CodeBlock?): List<CodeBlock> =
        if (mod == null) emptyList() else listOf(named("modifier", mod))

    private fun named(name: String, value: CodeBlock): CodeBlock = CodeBlock.of("%L = %L", name, value)

    /** Children as a trailing-lambda body; hidden nodes excluded from codegen (DATA_MODEL §5). */
    private fun body(children: List<Node>): CodeBlock {
        val b = CodeBlock.builder()
        children.filterNot { it.hidden }.forEach { b.add("%L\n", emit(it, isRoot = false)) }
        return b.build()
    }

    /**
     * Formats a call: 0 args → `Foo()` (or `Foo` when a trailing lambda follows), 1 arg → single
     * line, ≥2 → one per line with a trailing comma. [content] non-null appends a `{ … }` lambda.
     */
    private fun call(callee: MemberName, args: List<CodeBlock>, content: CodeBlock?): CodeBlock {
        val b = CodeBlock.builder()
        when {
            args.isEmpty() -> if (content == null) b.add("%M()", callee) else b.add("%M", callee)
            args.size == 1 -> b.add("%M(%L)", callee, args[0])
            else -> {
                b.add("%M(\n", callee).indent()
                args.forEach { b.add("%L,\n", it) }
                b.unindent().add(")")
            }
        }
        if (content != null) {
            b.add(" {\n").indent().add(content).unindent().add("}")
        }
        return b.build()
    }
}

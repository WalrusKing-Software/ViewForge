package viewforge.packages.compose.codegen

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import viewforge.model.Asset
import viewforge.model.ComponentDef
import viewforge.model.Node
import viewforge.model.PropValue
import viewforge.model.Theme
import viewforge.model.UserComponent

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
 * [components] resolves a `vforge.userComponent` instance to the definition it references, so the
 * instance emits a call to that component's generated composable (ADR-024) — one definition, many call
 * sites, which is how an edit to the definition reaches every instance.
 */
internal class ComponentEmitter(
    private val theme: Theme,
    assets: List<Asset> = emptyList(),
    components: List<ComponentDef> = emptyList(),
) {
    private val assetsById: Map<String, Asset> = assets.associateBy { it.id }
    private val componentsById: Map<String, ComponentDef> = components.associateBy { it.id }

    /**
     * Set true while emitting a tree that used an experimental Material3 API (e.g. `TopAppBar`), so the
     * caller can annotate the generated function `@OptIn(ExperimentalMaterial3Api::class)`. Read after
     * [emit] returns.
     */
    var requiresMaterial3OptIn: Boolean = false
        private set

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
            "compose.material3.Button" -> button(ComposeNames.Button, node, mod)
            "compose.material3.OutlinedButton" -> button(ComposeNames.OutlinedButton, node, mod)
            "compose.material3.TextButton" -> button(ComposeNames.TextButton, node, mod)
            "compose.material3.Slider" -> call(ComposeNames.Slider, sliderArgs(node, mod), content = null)
            "compose.material3.TextField" -> call(ComposeNames.TextField, textFieldArgs(node, mod), content = null)
            "compose.material3.OutlinedTextField" ->
                call(ComposeNames.OutlinedTextField, textFieldArgs(node, mod), content = null)
            "compose.material3.CircularProgressIndicator" ->
                call(ComposeNames.CircularProgressIndicator, modifierArg(mod), content = null)
            "compose.material3.LinearProgressIndicator" ->
                call(ComposeNames.LinearProgressIndicator, modifierArg(mod), content = null)
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
            "compose.material3.Icon" -> call(ComposeNames.Icon, iconArgs(node, mod), content = null)
            "compose.material3.TopAppBar" -> topAppBar(node, mod)
            "compose.material3.BottomAppBar" -> layout(ComposeNames.BottomAppBar, node, mod, emptyList())
            "compose.material3.Scaffold" -> scaffold(node, mod)
            UserComponent.TYPE -> userComponentCall(node, mod)
            else -> throw CodegenException("Unsupported component '${node.type}'")
        }
    }

    /**
     * A `vforge.userComponent` instance emits a call to the referenced component's generated composable,
     * `PrimaryButton(label = "Hi", modifier = …)` — the reference, not an inlined copy (ADR-024). For
     * each of the definition's parameters the instance supplies an argument value from its own props
     * (keyed by parameter name); a parameter the instance omits falls back to the definition's default,
     * or fails loudly if it has none. The instance's own modifier chain (if any) is passed last as the
     * `modifier` argument. An unresolved reference or a missing required argument fails loudly rather
     * than emitting a call that won't compile (CLAUDE.md: a visible error beats a silent wrong emit).
     */
    private fun userComponentCall(node: Node, mod: CodeBlock?): CodeBlock {
        val id = (node.props[UserComponent.COMPONENT_ID_PROP] as? PropValue.Literal)?.value?.content
        val def = id?.let { componentsById[it] }
            ?: throw CodegenException("Unresolved user-component instance: no component with id '${id ?: "?"}'")
        val fnName = KotlinIdentifiers.requireFunctionName(def.name)
        val args = buildList {
            def.parameters.forEach { p ->
                val arg = node.props[p.name]
                when {
                    arg != null -> add(named(p.name, ParameterTypes.argValue(p.type, arg, theme)))
                    // No argument and no default: the generated call would omit a required parameter.
                    p.default == null -> throw CodegenException(
                        "Instance of '${def.name}' is missing required argument '${p.name}'",
                    )
                    // else: parameter has a default — omit the argument so the default applies.
                }
            }
            if (mod != null) add(named("modifier", mod))
        }
        return componentCall(fnName, args)
    }

    /** Formats a call to a locally-generated composable by name: `Foo()`, `Foo(a)`, or multi-line. */
    private fun componentCall(fnName: String, args: List<CodeBlock>): CodeBlock {
        val b = CodeBlock.builder()
        when {
            args.isEmpty() -> b.add("%L()", fnName)
            args.size == 1 -> b.add("%L(%L)", fnName, args[0])
            else -> {
                b.add("%L(\n", fnName).indent()
                args.forEach { b.add("%L,\n", it) }
                b.unindent().add(")")
            }
        }
        return b.build()
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

    // Arg order mirrors the `Text` composable signature (and RenderText): text, modifier, color,
    // fontSize, fontStyle, fontWeight, letterSpacing, textDecoration, textAlign, lineHeight, overflow,
    // maxLines, style. Each optional arg is emitted only when the prop is present, so a plain Text
    // stays `Text(text = …)`.
    private fun textArgs(node: Node, mod: CodeBlock?): List<CodeBlock> = buildList {
        add(named("text", CodegenValues.text(node.props["text"])))
        if (mod != null) add(named("modifier", mod))
        node.props["color"]?.let { add(named("color", CodegenValues.color(it, theme))) }
        node.props["fontSize"]?.let { add(named("fontSize", CodegenValues.sp(it))) }
        node.props["fontStyle"]?.let { add(named("fontStyle", CodegenValues.enum("fontStyle", it))) }
        node.props["fontWeight"]?.let { add(named("fontWeight", CodegenValues.enum("fontWeight", it))) }
        node.props["letterSpacing"]?.let { add(named("letterSpacing", CodegenValues.sp(it))) }
        node.props["textDecoration"]?.let { add(named("textDecoration", CodegenValues.enum("textDecoration", it))) }
        node.props["textAlign"]?.let { add(named("textAlign", CodegenValues.enum("textAlign", it))) }
        node.props["lineHeight"]?.let { add(named("lineHeight", CodegenValues.sp(it))) }
        node.props["overflow"]?.let { add(named("overflow", CodegenValues.enum("overflow", it))) }
        node.props["maxLines"]?.let { add(named("maxLines", CodegenValues.int(it))) }
        node.props["style"]?.let { add(named("style", CodegenValues.typography(it, theme))) }
    }

    // Arg order mirrors the `Image` composable signature (and RenderImage): painter, contentDescription,
    // modifier, alignment, contentScale, alpha. Each optional arg is emitted only when its prop is set.
    private fun imageArgs(node: Node, mod: CodeBlock?): List<CodeBlock> = buildList {
        add(named("painter", CodegenValues.painter(node.props["source"], assetsById)))
        add(named("contentDescription", CodegenValues.nullableString(node.props["contentDescription"])))
        if (mod != null) add(named("modifier", mod))
        node.props["alignment"]?.let { add(named("alignment", CodegenValues.enum("alignment", it))) }
        node.props["contentScale"]?.let { add(named("contentScale", CodegenValues.enum("contentScale", it))) }
        node.props["alpha"]?.let { add(named("alpha", CodegenValues.float(it))) }
    }

    /** `Icon`: imageVector (a curated `Icons.Filled.*`), contentDescription, modifier (order mirrors the renderer). */
    private fun iconArgs(node: Node, mod: CodeBlock?): List<CodeBlock> = buildList {
        add(named("imageVector", CodegenValues.enum("icon", node.props["icon"])))
        add(named("contentDescription", CodegenValues.nullableString(node.props["contentDescription"])))
        if (mod != null) add(named("modifier", mod))
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

    /**
     * `TopAppBar`: a `title` slot (required) then modifier. Experimental Material3, so it flags the
     * screen for an `@OptIn(ExperimentalMaterial3Api::class)` annotation.
     */
    private fun topAppBar(node: Node, mod: CodeBlock?): CodeBlock {
        requiresMaterial3OptIn = true
        val args = buildList {
            add(slotArg("title", node.slots["title"].orEmpty()))
            if (mod != null) add(named("modifier", mod))
        }
        return call(ComposeNames.TopAppBar, args, content = null)
    }

    /** A named slot emitted as a `name = { … }` lambda argument (hidden children dropped). */
    private fun slotArg(name: String, children: List<Node>): CodeBlock = CodeBlock.builder()
        .add("%L = {\n", name)
        .indent()
        .add(body(children))
        .unindent()
        .add("}")
        .build()

    /**
     * `Button`/`OutlinedButton`/`TextButton` share a signature: onClick, modifier, enabled, shape,
     * colors, elevation, contentPadding, then a content slot. The styling args come from props only the filled
     * `Button` advertises (issue #17), so the `ButtonDefaults.button*` factories are correct here; the
     * outlined/text variants would need their own factories if ever extended.
     */
    private fun button(callee: MemberName, node: Node, mod: CodeBlock?): CodeBlock {
        val args = buildList {
            add(named("onClick", CodegenValues.lambda(node.props["onClick"])))
            if (mod != null) add(named("modifier", mod))
            node.props["enabled"]?.let { add(named("enabled", CodegenValues.bool(it))) }
            node.props["shape"]?.let { add(named("shape", CodegenValues.shape(it, theme))) }
            CodegenValues.buttonColors(node.props["containerColor"], node.props["contentColor"], theme)
                ?.let { add(named("colors", it)) }
            node.props["elevation"]?.let { add(named("elevation", CodegenValues.buttonElevation(it))) }
            node.props["contentPadding"]?.let { add(named("contentPadding", CodegenValues.contentPadding(it))) }
        }
        return call(callee, args, content = body(node.slots["content"].orEmpty()))
    }

    /** `Slider`: value, onValueChange, modifier, enabled (order mirrors the renderer). */
    private fun sliderArgs(node: Node, mod: CodeBlock?): List<CodeBlock> = buildList {
        add(named("value", CodegenValues.float(node.props["value"])))
        add(named("onValueChange", CodegenValues.lambda(node.props["onValueChange"])))
        if (mod != null) add(named("modifier", mod))
        node.props["enabled"]?.let { add(named("enabled", CodegenValues.bool(it))) }
    }

    /** `TextField`/`OutlinedTextField` share: value (String), onValueChange, modifier, enabled. */
    private fun textFieldArgs(node: Node, mod: CodeBlock?): List<CodeBlock> = buildList {
        add(named("value", CodegenValues.text(node.props["value"])))
        add(named("onValueChange", CodegenValues.lambda(node.props["onValueChange"])))
        if (mod != null) add(named("modifier", mod))
        node.props["enabled"]?.let { add(named("enabled", CodegenValues.bool(it))) }
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
     * line, ≥2 → one per line with a trailing comma. [content] non-null appends a `{ … }` lambda;
     * [contentParam] names its single parameter (`{ innerPadding -> … }`) for slots that receive one.
     */
    private fun call(
        callee: MemberName,
        args: List<CodeBlock>,
        content: CodeBlock?,
        contentParam: String? = null,
    ): CodeBlock {
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
            if (contentParam == null) b.add(" {\n") else b.add(" { %L ->\n", contentParam)
            b.indent().add(content).unindent().add("}")
        }
        return b.build()
    }

    /**
     * `Scaffold`: `topBar`/`bottomBar` named slots (omitted when empty), then a `content` lambda that
     * receives `innerPadding: PaddingValues`. The content children are wrapped in a
     * `Column(Modifier.padding(innerPadding))` so the generated code consumes the inset (avoiding
     * Compose's `UnusedMaterialScaffoldPaddingParameter` lint) — the same wrapping the renderer draws,
     * so canvas and output still agree (TECHNICAL_NOTES §2). `Scaffold` itself is stable (no opt-in).
     */
    private fun scaffold(node: Node, mod: CodeBlock?): CodeBlock {
        val args = buildList {
            if (mod != null) add(named("modifier", mod))
            node.slots["topBar"].orEmpty().let { if (it.isNotEmpty()) add(slotArg("topBar", it)) }
            node.slots["bottomBar"].orEmpty().let { if (it.isNotEmpty()) add(slotArg("bottomBar", it)) }
        }
        val content = CodeBlock.builder()
            .add(
                "%M(modifier = %T.%M(innerPadding)) {\n",
                ComposeNames.Column,
                ComposeNames.Modifier,
                ComposeNames.padding,
            )
            .indent()
            .add(body(node.children))
            .unindent()
            .add("}\n")
            .build()
        return call(ComposeNames.Scaffold, args, content = content, contentParam = "innerPadding")
    }
}

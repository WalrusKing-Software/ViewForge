package viewforge.packages.compose.codegen

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import viewforge.model.Asset
import viewforge.model.BindingTypeScope
import viewforge.model.ComponentDef
import viewforge.model.Dropdown
import viewforge.model.EventSlots
import viewforge.model.Node
import viewforge.model.PropValue
import viewforge.model.Repeater
import viewforge.model.ScalarType
import viewforge.model.StateField
import viewforge.model.Theme
import viewforge.model.UserComponent
import viewforge.model.effectiveProps
import viewforge.model.resolveBindingType
import viewforge.model.resolveListShape
import viewforge.packages.compose.targets.AndroidTarget
import viewforge.spi.Breakpoint

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
 *
 * [breakpoints] are the responsive thresholds codegen branches on (ADR-037, #222); defaulted from the
 * Android target that owns them (`core` keeps breakpoint ids opaque, ADR-030).
 */
internal class ComponentEmitter(
    private val theme: Theme,
    assets: List<Asset> = emptyList(),
    components: List<ComponentDef> = emptyList(),
    private val recordSpans: Boolean = false,
    private val state: List<StateField> = emptyList(),
    private val breakpoints: List<Breakpoint> = AndroidTarget.breakpoints,
    private val imageResources: ImageResources = ImageResources.Desktop,
) {
    private val assetsById: Map<String, Asset> = assets.associateBy { it.id }
    private val componentsById: Map<String, ComponentDef> = components.associateBy { it.id }

    /** The binding scope at the surface root: the owner's declared [state], with no repeat item in scope yet. */
    private val screenScope: BindingTypeScope get() = BindingTypeScope(fields = state)

    /** A named call argument, `name = value`. Kept split (not pre-joined) so responsive codegen can hoist an
     *  overridden prop's [value] into a `val` and rename the argument to reference that local (#222). */
    private data class Arg(val name: String, val value: CodeBlock)

    /**
     * Whether [value] is a [PropValue.StateBinding] to a numeric (INT/FLOAT) field under [scope] — the case a
     * String-typed prop must coerce with `.toString()` (#298). Resolved via [resolveBindingType], the shared
     * authority the renderer and inspector also use, so `item.<field>` inside a repeat resolves against the row.
     */
    private fun numericBinding(value: PropValue?, scope: BindingTypeScope): Boolean {
        val path = (value as? PropValue.StateBinding)?.path ?: return false
        return resolveBindingType(path, scope) in setOf(ScalarType.INT, ScalarType.FLOAT)
    }

    /**
     * Set true while emitting a tree that used an experimental Material3 API (e.g. `TopAppBar`), so the
     * caller can annotate the generated function `@OptIn(ExperimentalMaterial3Api::class)`. Read after
     * [emit] returns.
     */
    var requiresMaterial3OptIn: Boolean = false
        private set

    /**
     * Emits [node]. [isRoot] chains its modifier onto the composable's `modifier` parameter. When
     * [recordSpans] is set, the node's code is bracketed with [SourceSpans] marker lines (G3, #51) — a
     * whole line before and after, so stripping them restores the exact un-instrumented output. Because
     * `emit` is only ever embedded as a statement (a body line, a lazy `item { }`, a slot lambda), the
     * markers always land on their own lines, covering nodes inside slots and lazy lists too.
     */
    fun emit(
        node: Node,
        isRoot: Boolean,
        parentAllowsWeight: Boolean = false,
        scope: BindingTypeScope = screenScope,
    ): CodeBlock {
        val core = emitCore(node, isRoot, parentAllowsWeight, scope)
        return if (!recordSpans) {
            core
        } else {
            CodeBlock.builder()
                .add("%L\n", SourceSpans.open(node.id.value))
                .add(core)
                .add("\n%L", SourceSpans.close(node.id.value))
                .build()
        }
    }

    private fun emitCore(node: Node, isRoot: Boolean, parentAllowsWeight: Boolean, scope: BindingTypeScope): CodeBlock {
        val mod = if (isRoot) {
            ModifierEmitter.rootChain(node.modifiers, theme)
        } else {
            ModifierEmitter.nodeChain(node.modifiers, theme, allowWeight = parentAllowsWeight)
        }
        return when (node.type) {
            // Row/Column establish the RowScope/ColumnScope their direct children may `weight` into (#158).
            "compose.foundation.layout.Column" ->
                layout(ComposeNames.Column, node, mod, scope, childrenGetWeight = true) { columnArgs(it) }
            "compose.foundation.layout.Row" ->
                layout(ComposeNames.Row, node, mod, scope, childrenGetWeight = true) { rowArgs(it) }
            "compose.foundation.layout.Box" -> layout(ComposeNames.Box, node, mod, scope) { boxArgs(it) }
            "compose.foundation.layout.Spacer" ->
                responsiveCall(node, ComposeNames.Spacer, content = null) { modifierArg(mod) }
            "compose.foundation.lazy.LazyColumn" -> lazyList(ComposeNames.LazyColumn, node, mod, scope) {
                columnArgs(it)
            }
            "compose.foundation.lazy.LazyRow" -> lazyList(ComposeNames.LazyRow, node, mod, scope) { rowArgs(it) }
            "compose.material3.Text" ->
                responsiveCall(node, ComposeNames.Text, content = null) { textArgs(it, mod, scope) }
            "compose.material3.Button" -> button(ComposeNames.Button, node, mod, scope)
            "compose.material3.OutlinedButton" -> button(ComposeNames.OutlinedButton, node, mod, scope)
            "compose.material3.TextButton" -> button(ComposeNames.TextButton, node, mod, scope)
            "compose.material3.Slider" ->
                responsiveCall(node, ComposeNames.Slider, content = null) { sliderArgs(it, mod) }
            "compose.material3.TextField" ->
                responsiveCall(node, ComposeNames.TextField, content = null) { textFieldArgs(it, mod, scope) }
            "compose.material3.OutlinedTextField" ->
                responsiveCall(node, ComposeNames.OutlinedTextField, content = null) { textFieldArgs(it, mod, scope) }
            "compose.material3.CircularProgressIndicator" ->
                responsiveCall(node, ComposeNames.CircularProgressIndicator, content = null) { modifierArg(mod) }
            "compose.material3.LinearProgressIndicator" ->
                responsiveCall(node, ComposeNames.LinearProgressIndicator, content = null) { modifierArg(mod) }
            "compose.material3.Card" -> layout(ComposeNames.Card, node, mod, scope) { emptyList() }
            "compose.material3.Surface" -> layout(ComposeNames.Surface, node, mod, scope) { emptyList() }
            "compose.material3.HorizontalDivider" ->
                responsiveCall(node, ComposeNames.HorizontalDivider, content = null) { dividerArgs(it, mod) }
            "compose.material3.Checkbox" ->
                responsiveCall(node, ComposeNames.Checkbox, content = null) { toggleArgs(it, mod) }
            "compose.material3.Switch" ->
                responsiveCall(node, ComposeNames.Switch, content = null) { toggleArgs(it, mod) }
            "compose.foundation.Image" ->
                responsiveCall(node, ComposeNames.Image, content = null) { imageArgs(it, mod, scope) }
            "compose.material3.Icon" ->
                responsiveCall(node, ComposeNames.Icon, content = null) { iconArgs(it, mod, scope) }
            "compose.material3.TopAppBar" -> topAppBar(node, mod, scope)
            "compose.material3.BottomAppBar" -> layout(ComposeNames.BottomAppBar, node, mod, scope) { emptyList() }
            "compose.material3.Scaffold" -> scaffold(node, mod, scope)
            UserComponent.TYPE -> userComponentCall(node, mod)
            Repeater.TYPE -> repeater(node, parentAllowsWeight, scope)
            Dropdown.TYPE -> dropdown(node, mod)
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
        guardNoResponsive(node)
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

    /**
     * A `vforge.repeat` over a list-typed state field (ADR-034, #21): the template ([Repeater] children) once
     * per element, the element bound to the `item` scope so an `item.<field>` [PropValue.StateBinding] in the
     * template emits as member access. Two layouts (ADR-034 slice 2, [Repeater.layoutOf]):
     * - **forEach** (default) — `source.forEach { item -> … }`, items landing inline in the parent's flow,
     *   mirroring the renderer's in-place expansion. [parentAllowsWeight] is forwarded so a repeated Row/Column
     *   child may `weight`.
     * - **lazyColumn** — `LazyColumn { items(source) { item -> … } }`, a scrolling list. Items sit in a
     *   `LazyItemScope`, not the parent Row/Column, so `weight` does not apply and is not forwarded.
     */
    private fun repeater(node: Node, parentAllowsWeight: Boolean, scope: BindingTypeScope): CodeBlock {
        guardNoResponsive(node)
        val source = Repeater.sourceOf(node)
            ?: throw CodegenException("vforge.repeat node '${node.id.value}' has no source binding")
        val sourceRef = CodegenValues.bindingPath(source)
        // The template body binds against the row: `item.<field>` resolves to the source list's record fields, so
        // a numeric record field bound to a String prop is coerced correctly (#298). Nested lists (#255) resolve
        // the source in the *current* scope, so an inner repeat shadows the outer item.
        val itemScope = scope.copy(itemFields = resolveListShape(source, scope))
        return if (Repeater.isLazyColumn(node)) {
            CodeBlock.builder()
                .add("%M {\n", ComposeNames.LazyColumn)
                .indent()
                .add("%M(%L) { %N ->\n", ComposeNames.lazyItems, sourceRef, Repeater.ITEM_SCOPE)
                .indent()
                .add(body(node.children, itemScope, allowWeight = false))
                .unindent()
                .add("}\n")
                .unindent()
                .add("}")
                .build()
        } else {
            CodeBlock.builder()
                .add("%L.forEach { %N ->\n", sourceRef, Repeater.ITEM_SCOPE)
                .indent()
                .add(body(node.children, itemScope, allowWeight = parentAllowsWeight))
                .unindent()
                .add("}")
                .build()
        }
    }

    /**
     * A `vforge.dropdown` populated (read-only) from a list-of-record state field (ADR-034 slice 2, #253): a
     * `Box` holding a read-only anchor `OutlinedTextField` (showing the first row's label, mirroring the canvas
     * preview) over a `DropdownMenu` whose items are `options.forEach { item -> DropdownMenuItem(...) }`. The
     * options binding emits as member access ([CodegenValues.bindingPath]) reading the seeded state stub; the
     * label field selects which record field is shown. Handlers are inert stubs (`expanded = false`, empty
     * lambdas) — the same house style as the generated `Checkbox`/`Slider`: structure emitted, behavior left to
     * the developer (read-only this release, no selection persisted; PF-4). Options/label absent → fail loud.
     */
    private fun dropdown(node: Node, mod: CodeBlock?): CodeBlock {
        guardNoResponsive(node)
        val source = Dropdown.optionsOf(node)?.takeIf { it.isNotBlank() }
            ?: throw CodegenException("vforge.dropdown node '${node.id.value}' has no options binding")
        val sourceRef = CodegenValues.bindingPath(source)
        val label = Dropdown.labelFieldOf(node)
            ?: throw CodegenException("vforge.dropdown node '${node.id.value}' has no label field")
        val content = CodeBlock.builder()
            .add("%M(\n", ComposeNames.OutlinedTextField).indent()
            .add("value = %L.firstOrNull()?.%N.orEmpty(),\n", sourceRef, label)
            .add("onValueChange = {},\n")
            .add("readOnly = true,\n")
            .add(
                "trailingIcon = { %M(%T.%M, contentDescription = null) },\n",
                ComposeNames.Icon,
                ComposeNames.IconsFilled,
                ComposeNames.iconMember("ArrowDropDown"),
            )
            .unindent().add(")\n")
            .add("%M(expanded = false, onDismissRequest = {}) {\n", ComposeNames.DropdownMenu).indent()
            .add("%L.forEach { %N ->\n", sourceRef, Repeater.ITEM_SCOPE).indent()
            .add("%M(\n", ComposeNames.DropdownMenuItem).indent()
            .add("text = { %M(%N.%N) },\n", ComposeNames.Text, Repeater.ITEM_SCOPE, label)
            .add("onClick = {},\n")
            .unindent().add(")\n")
            .unindent().add("}\n")
            .unindent().add("}\n")
            .build()
        return call(ComposeNames.Box, modifierArg(mod), content = content)
    }

    /** Formats a call to a locally-generated composable by name: `Foo()`, `Foo(a)`, or multi-line. */
    private fun componentCall(fnName: String, args: List<Arg>): CodeBlock {
        val b = CodeBlock.builder()
        when {
            args.isEmpty() -> b.add("%L()", fnName)
            args.size == 1 -> b.add("%L(%L = %L)", fnName, args[0].name, args[0].value)
            else -> {
                b.add("%L(\n", fnName).indent()
                args.forEach { b.add("%L = %L,\n", it.name, it.value) }
                b.unindent().add(")")
            }
        }
        return b.build()
    }

    // --- per-component argument lists (order mirrors the renderer's Composable call) --------------
    // Each takes a props map (not the node) so responsiveCall can rebuild the list per breakpoint from
    // effectiveProps; modifier/scope are constant across breakpoints and passed through.

    private fun columnArgs(props: Map<String, PropValue>): List<Arg> = buildList {
        props["verticalArrangement"]?.let {
            add(named("verticalArrangement", CodegenValues.enum("verticalArrangement", it)))
        }
        props["horizontalAlignment"]?.let {
            add(named("horizontalAlignment", CodegenValues.enum("horizontalAlignment", it)))
        }
    }

    private fun rowArgs(props: Map<String, PropValue>): List<Arg> = buildList {
        props["horizontalArrangement"]?.let {
            add(named("horizontalArrangement", CodegenValues.enum("horizontalArrangement", it)))
        }
        props["verticalAlignment"]?.let {
            add(named("verticalAlignment", CodegenValues.enum("verticalAlignment", it)))
        }
    }

    private fun boxArgs(props: Map<String, PropValue>): List<Arg> = buildList {
        props["contentAlignment"]?.let {
            add(named("contentAlignment", CodegenValues.enum("contentAlignment", it)))
        }
    }

    // Arg order mirrors the `Text` composable signature (and RenderText): text, modifier, color,
    // fontSize, fontStyle, fontWeight, letterSpacing, textDecoration, textAlign, lineHeight, overflow,
    // maxLines, style. Each optional arg is emitted only when the prop is present, so a plain Text
    // stays `Text(text = …)`.
    private fun textArgs(props: Map<String, PropValue>, mod: CodeBlock?, scope: BindingTypeScope): List<Arg> =
        buildList {
            add(named("text", CodegenValues.text(props["text"], numericBinding(props["text"], scope))))
            if (mod != null) add(named("modifier", mod))
            props["color"]?.let { add(named("color", CodegenValues.color(it, theme))) }
            props["fontSize"]?.let { add(named("fontSize", CodegenValues.sp(it))) }
            props["fontStyle"]?.let { add(named("fontStyle", CodegenValues.enum("fontStyle", it))) }
            props["fontWeight"]?.let { add(named("fontWeight", CodegenValues.enum("fontWeight", it))) }
            props["letterSpacing"]?.let { add(named("letterSpacing", CodegenValues.sp(it))) }
            props["textDecoration"]?.let { add(named("textDecoration", CodegenValues.enum("textDecoration", it))) }
            props["textAlign"]?.let { add(named("textAlign", CodegenValues.enum("textAlign", it))) }
            props["lineHeight"]?.let { add(named("lineHeight", CodegenValues.sp(it))) }
            props["overflow"]?.let { add(named("overflow", CodegenValues.enum("overflow", it))) }
            props["maxLines"]?.let { add(named("maxLines", CodegenValues.int(it))) }
            props["style"]?.let { add(named("style", CodegenValues.typography(it, theme))) }
        }

    // Arg order mirrors the `Image` composable signature (and RenderImage): painter, contentDescription,
    // modifier, alignment, contentScale, alpha. Each optional arg is emitted only when its prop is set.
    private fun imageArgs(props: Map<String, PropValue>, mod: CodeBlock?, scope: BindingTypeScope): List<Arg> =
        buildList {
            add(named("painter", CodegenValues.painter(props["source"], assetsById, imageResources)))
            add(
                named(
                    "contentDescription",
                    CodegenValues.nullableString(
                        props["contentDescription"],
                        numericBinding(props["contentDescription"], scope),
                    ),
                ),
            )
            if (mod != null) add(named("modifier", mod))
            props["alignment"]?.let { add(named("alignment", CodegenValues.enum("alignment", it))) }
            props["contentScale"]?.let { add(named("contentScale", CodegenValues.enum("contentScale", it))) }
            props["alpha"]?.let { add(named("alpha", CodegenValues.float(it))) }
        }

    /** `Icon`: imageVector (a curated `Icons.Filled.*`), contentDescription, modifier (order mirrors the renderer). */
    private fun iconArgs(props: Map<String, PropValue>, mod: CodeBlock?, scope: BindingTypeScope): List<Arg> =
        buildList {
            add(named("imageVector", CodegenValues.enum("icon", props["icon"])))
            add(
                named(
                    "contentDescription",
                    CodegenValues.nullableString(
                        props["contentDescription"],
                        numericBinding(props["contentDescription"], scope),
                    ),
                ),
            )
            if (mod != null) add(named("modifier", mod))
        }

    private fun dividerArgs(props: Map<String, PropValue>, mod: CodeBlock?): List<Arg> = buildList {
        if (mod != null) add(named("modifier", mod))
        props["thickness"]?.let { add(named("thickness", CodegenValues.dpProp(it))) }
    }

    /** `Checkbox`/`Switch` share a signature: checked, onCheckedChange, modifier, enabled (order mirrors the renderer). */
    private fun toggleArgs(props: Map<String, PropValue>, mod: CodeBlock?): List<Arg> = buildList {
        add(named("checked", CodegenValues.bool(props["checked"])))
        add(named("onCheckedChange", CodegenValues.lambda(props["onCheckedChange"])))
        if (mod != null) add(named("modifier", mod))
        props["enabled"]?.let { add(named("enabled", CodegenValues.bool(it))) }
    }

    // --- shape helpers ---------------------------------------------------------------------------

    /**
     * A layout container: its own args, then children (hidden dropped) as the trailing lambda.
     * [childrenGetWeight] is true only for Row/Column, whose direct children may carry a `weight` (#158).
     * [extraArgs] builds the container's props-derived args from a props map so responsiveCall can rebuild
     * them per breakpoint; the modifier is prepended (and, being breakpoint-invariant, never hoisted).
     */
    private fun layout(
        callee: MemberName,
        node: Node,
        mod: CodeBlock?,
        scope: BindingTypeScope,
        childrenGetWeight: Boolean = false,
        extraArgs: (Map<String, PropValue>) -> List<Arg>,
    ): CodeBlock {
        val content = body(node.children, scope, childrenGetWeight)
        return responsiveCall(node, callee, content) { props -> modifierArg(mod) + extraArgs(props) }
    }

    /**
     * A lazy list (`LazyColumn`/`LazyRow`): same args as its eager twin, but each static child is
     * wrapped in its own `item { … }` — the `LazyListScope` DSL, mirroring the renderer (DATA_MODEL
     * §12.2: static children only in Phase 1).
     */
    private fun lazyList(
        callee: MemberName,
        node: Node,
        mod: CodeBlock?,
        scope: BindingTypeScope,
        extraArgs: (Map<String, PropValue>) -> List<Arg>,
    ): CodeBlock {
        val content = lazyBody(node.children, scope)
        return responsiveCall(node, callee, content) { props -> modifierArg(mod) + extraArgs(props) }
    }

    /** Children as `item { … }` entries; hidden nodes excluded from codegen (DATA_MODEL §5). */
    private fun lazyBody(children: List<Node>, scope: BindingTypeScope): CodeBlock {
        val b = CodeBlock.builder()
        children.filterNot { it.hidden }.forEach { child ->
            b.add("item {\n").indent().add("%L\n", emit(child, isRoot = false, scope = scope)).unindent().add("}\n")
        }
        return b.build()
    }

    /**
     * `TopAppBar`: a `title` slot (required) then modifier. Experimental Material3, so it flags the
     * screen for an `@OptIn(ExperimentalMaterial3Api::class)` annotation.
     */
    private fun topAppBar(node: Node, mod: CodeBlock?, scope: BindingTypeScope): CodeBlock {
        guardNoResponsive(node)
        requiresMaterial3OptIn = true
        val args = buildList {
            add(slotArg("title", node.slots["title"].orEmpty(), scope))
            if (mod != null) add(named("modifier", mod))
        }
        return call(ComposeNames.TopAppBar, args, content = null)
    }

    /** A named slot emitted as a `name = { … }` lambda argument (hidden children dropped). */
    private fun slotArg(name: String, children: List<Node>, scope: BindingTypeScope): Arg = Arg(
        name,
        CodeBlock.builder()
            .add("{\n")
            .indent()
            .add(body(children, scope))
            .unindent()
            .add("}")
            .build(),
    )

    /**
     * `Button`/`OutlinedButton`/`TextButton` share a signature: onClick, modifier, enabled, shape,
     * colors, elevation, contentPadding, then a content slot. The styling args come from props only the filled
     * `Button` advertises (issue #17), so the `ButtonDefaults.button*` factories are correct here; the
     * outlined/text variants would need their own factories if ever extended.
     */
    private fun button(callee: MemberName, node: Node, mod: CodeBlock?, scope: BindingTypeScope): CodeBlock {
        val content = body(node.slots["content"].orEmpty(), scope)
        return responsiveCall(node, callee, content) { props -> buttonArgs(props, node, mod) }
    }

    /** The filled button's args from a props map ([button]); `onClick` reads the node's handlers, not props. */
    private fun buttonArgs(props: Map<String, PropValue>, node: Node, mod: CodeBlock?): List<Arg> = buildList {
        add(named("onClick", onClickArg(node)))
        if (mod != null) add(named("modifier", mod))
        props["enabled"]?.let { add(named("enabled", CodegenValues.bool(it))) }
        props["shape"]?.let { add(named("shape", CodegenValues.shape(it, theme))) }
        CodegenValues.buttonColors(props["containerColor"], props["contentColor"], theme)
            ?.let { add(named("colors", it)) }
        props["elevation"]?.let { add(named("elevation", CodegenValues.buttonElevation(it))) }
        props["contentPadding"]?.let { add(named("contentPadding", CodegenValues.contentPadding(it))) }
    }

    /**
     * A Button's `onClick` argument (ADR-035, #277): when the node carries an `onClick` [Node.handlers] slot, a
     * structural `{ <lowered actions> }` lambda built from the closed [viewforge.model.Action] list ([StateEmitter.handlerBody])
     * — real interactive code, not the inert stub. With no handler it falls back to the ADR-034 inert lambda
     * (the static-preview/no-op path), so a handler-free button is byte-identical to before.
     */
    private fun onClickArg(node: Node): CodeBlock {
        val handler = node.handlers[EventSlots.ON_CLICK].orEmpty()
        if (handler.isEmpty()) return CodegenValues.lambda(node.props["onClick"])
        return CodeBlock.builder()
            .add("{\n").indent()
            .add(StateEmitter.handlerBody(handler, state))
            .unindent().add("}")
            .build()
    }

    /** `Slider`: value, onValueChange, modifier, enabled (order mirrors the renderer). */
    private fun sliderArgs(props: Map<String, PropValue>, mod: CodeBlock?): List<Arg> = buildList {
        add(named("value", CodegenValues.float(props["value"])))
        add(named("onValueChange", CodegenValues.lambda(props["onValueChange"])))
        if (mod != null) add(named("modifier", mod))
        props["enabled"]?.let { add(named("enabled", CodegenValues.bool(it))) }
    }

    /** `TextField`/`OutlinedTextField` share: value (String), onValueChange, modifier, enabled. */
    private fun textFieldArgs(props: Map<String, PropValue>, mod: CodeBlock?, scope: BindingTypeScope): List<Arg> =
        buildList {
            add(named("value", CodegenValues.text(props["value"], numericBinding(props["value"], scope))))
            add(named("onValueChange", CodegenValues.lambda(props["onValueChange"])))
            if (mod != null) add(named("modifier", mod))
            props["enabled"]?.let { add(named("enabled", CodegenValues.bool(it))) }
        }

    /** The `modifier = <chain>` argument, or nothing when a non-root node has no modifiers. */
    private fun modifierArg(mod: CodeBlock?): List<Arg> =
        if (mod == null) emptyList() else listOf(named("modifier", mod))

    private fun named(name: String, value: CodeBlock): Arg = Arg(name, value)

    /**
     * Children as a trailing-lambda body; hidden nodes excluded from codegen (DATA_MODEL §5). [allowWeight]
     * (true only for a Row/Column parent) lets each direct child emit a `weight` modifier (#158).
     */
    private fun body(children: List<Node>, scope: BindingTypeScope, allowWeight: Boolean = false): CodeBlock {
        val b = CodeBlock.builder()
        children.filterNot { it.hidden }
            .forEach { b.add("%L\n", emit(it, isRoot = false, parentAllowsWeight = allowWeight, scope = scope)) }
        return b.build()
    }

    /**
     * Emits [node] as a component call, applying responsive per-breakpoint overrides (#222, ADR-037). With no
     * overrides (the common case) this is exactly [call] on the base args, so output is byte-identical to before.
     * With overrides, each argument whose value differs across the base and the node's breakpoints is hoisted
     * into a `val` selected by `maxWidth` inside a `BoxWithConstraints`, and the call references that local — the
     * base (compact) value is the final `else`. [argsFor] rebuilds the arg list from a props map, so it can be
     * run once per breakpoint via [effectiveProps]; the [content], modifiers and handlers do not vary by
     * breakpoint and stay inline.
     *
     * Fails loud (CLAUDE.md) on an override that introduces a prop absent at the base breakpoint — there is no
     * `else` value to fall back to, so a base value must be set.
     */
    private fun responsiveCall(
        node: Node,
        callee: MemberName,
        content: CodeBlock?,
        contentParam: String? = null,
        argsFor: (Map<String, PropValue>) -> List<Arg>,
    ): CodeBlock {
        val baseArgs = argsFor(node.props)
        // Only the breakpoints this node actually overrides, largest-first for the if/else chain. An id the
        // target doesn't define (opaque to core) contributes nothing to branch on.
        val activeBps = breakpoints
            .filter { it.id in node.responsive.keys }
            .sortedByDescending { it.minWidthDp }
        if (activeBps.isEmpty()) return call(callee, baseArgs, content, contentParam)

        val baseByName = baseArgs.associate { it.name to it.value }
        val perBp: Map<Breakpoint, Map<String, CodeBlock>> =
            activeBps.associateWith { bp -> argsFor(effectiveProps(node, bp.id)).associate { it.name to it.value } }
        perBp.forEach { (bp, byName) ->
            byName.keys.forEach { name ->
                if (name !in baseByName) {
                    throw CodegenException(
                        "Responsive override at breakpoint '${bp.id}' introduces '$name' not set at the base " +
                            "breakpoint on node '${node.id.value}' — set a base value for it",
                    )
                }
            }
        }

        val hoists = CodeBlock.builder()
        var hoisted = false
        val mergedArgs = baseArgs.map { arg ->
            val differs = activeBps.any { bp ->
                perBp.getValue(bp).getValue(arg.name).toString() != arg.value.toString()
            }
            if (!differs) {
                arg
            } else {
                hoisted = true
                hoists.add("val %L = ", arg.name)
                activeBps.forEach { bp ->
                    hoists.add("if (maxWidth >= %L.%M) {\n", bp.minWidthDp, ComposeNames.dp)
                        .indent()
                        .add("%L\n", perBp.getValue(bp).getValue(arg.name))
                        .unindent()
                        .add("} else ")
                }
                hoists.add("{\n").indent().add("%L\n", arg.value).unindent().add("}\n")
                Arg(arg.name, CodeBlock.of("%L", arg.name))
            }
        }
        // Every override equalled the base (nothing to branch on): emit the plain call, no wrapper.
        if (!hoisted) return call(callee, baseArgs, content, contentParam)

        return CodeBlock.builder()
            .add("%M {\n", ComposeNames.BoxWithConstraints)
            .indent()
            .add(hoists.build())
            .add("%L\n", call(callee, mergedArgs, content, contentParam))
            .unindent()
            .add("}")
            .build()
    }

    /** Fails loud when a node carrying responsive overrides reaches an emitter that does not support them yet. */
    private fun guardNoResponsive(node: Node) {
        if (node.responsive.isNotEmpty()) {
            throw CodegenException(
                "Responsive per-breakpoint overrides are not supported on '${node.type}' " +
                    "(node '${node.id.value}') in this release",
            )
        }
    }

    /**
     * Formats a call: 0 args → `Foo()` (or `Foo` when a trailing lambda follows), 1 arg → single
     * line, ≥2 → one per line with a trailing comma. [content] non-null appends a `{ … }` lambda;
     * [contentParam] names its single parameter (`{ innerPadding -> … }`) for slots that receive one.
     */
    private fun call(
        callee: MemberName,
        args: List<Arg>,
        content: CodeBlock?,
        contentParam: String? = null,
    ): CodeBlock {
        val b = CodeBlock.builder()
        when {
            args.isEmpty() -> if (content == null) b.add("%M()", callee) else b.add("%M", callee)
            args.size == 1 -> b.add("%M(%L = %L)", callee, args[0].name, args[0].value)
            else -> {
                b.add("%M(\n", callee).indent()
                args.forEach { b.add("%L = %L,\n", it.name, it.value) }
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
    private fun scaffold(node: Node, mod: CodeBlock?, scope: BindingTypeScope): CodeBlock {
        guardNoResponsive(node)
        val args = buildList {
            if (mod != null) add(named("modifier", mod))
            node.slots["topBar"].orEmpty().let { if (it.isNotEmpty()) add(slotArg("topBar", it, scope)) }
            node.slots["bottomBar"].orEmpty().let { if (it.isNotEmpty()) add(slotArg("bottomBar", it, scope)) }
        }
        val content = CodeBlock.builder()
            .add(
                "%M(modifier = %T.%M(innerPadding)) {\n",
                ComposeNames.Column,
                ComposeNames.Modifier,
                ComposeNames.padding,
            )
            .indent()
            .add(body(node.children, scope))
            .unindent()
            .add("}\n")
            .build()
        return call(ComposeNames.Scaffold, args, content = content, contentParam = "innerPadding")
    }
}

package viewforge.packages.compose.codegen

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.joinToCode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import viewforge.model.Action
import viewforge.model.Node
import viewforge.model.PropValue
import viewforge.model.RecordField
import viewforge.model.SampleValue
import viewforge.model.ScalarType
import viewforge.model.StateField
import viewforge.model.StateType
import viewforge.model.resolveWritableScalar
import viewforge.model.resolveWritableTarget
import viewforge.model.scalarValue
import viewforge.model.targetPath

/**
 * Codegen for a screen's read-only state (ADR-034, #21): a generated `data class` per list-of-record type
 * and a seeded, runnable stub declaring one `val` per [StateField] from its sample data. Bindings then read
 * these locals as member access ([CodegenValues.bindingPath]); a repeat emits `source.forEach { item -> … }`.
 *
 * The stub is a **starting point, not the wiring** (D-note): it materialises the design-time sample so the
 * generated screen runs as-is, headed by a `// TODO` telling the developer to swap in their real source.
 * Everything is a structural KotlinPoet [CodeBlock]/[TypeSpec] (GC-1/GC-2) — sample values are typed literals,
 * never evaluated (PF-4).
 */
internal object StateEmitter {
    /**
     * The element `data class` for every list-of-record type reachable from [state] — top-level fields **and**
     * nested list fields (#255), collected transitively and deduplicated by type name (first shape wins; the
     * fixture must pick field names that singularise uniquely). Empty for a screen with no lists.
     */
    fun recordTypes(state: List<StateField>): List<TypeSpec> {
        val collected = LinkedHashMap<String, List<RecordField>>()
        state.forEach { collectRecordTypes(it.name, it.type, collected) }
        return collected.map { (name, fields) -> dataClass(name, fields) }
    }

    /** Depth-first collect: a list-of-record type contributes its own `data class`, then recurses into its fields. */
    private fun collectRecordTypes(fieldName: String, type: StateType, out: MutableMap<String, List<RecordField>>) {
        if (type !is StateType.ListOfRecord) return
        out.putIfAbsent(recordTypeName(fieldName), type.fields)
        type.fields.forEach { collectRecordTypes(it.name, it.type, out) }
    }

    /**
     * The seeded state block: a `// TODO` line then one declaration per field, or null when the screen has no
     * state (so a stateless screen's body is byte-identical to before). Prepended to the body.
     *
     * A field named in [writableTargets] — i.e. written by some handler [Action] (ADR-035, #277) — becomes a
     * mutable `var <name> by remember { mutableStateOf(<sample>) }`, so its handler assignments recompose the
     * screen; a field only ever *read* stays the read-only `val <name> = <sample>` of ADR-034. With no writable
     * fields (the pre-interactive case) every field is a `val`, keeping output byte-identical to before.
     */
    fun stubs(state: List<StateField>, writableTargets: Set<String> = emptySet()): CodeBlock? {
        if (state.isEmpty()) return null
        val b = CodeBlock.builder()
        b.add("// TODO: replace with your real data source\n")
        state.forEach { field ->
            if (field.name in writableTargets) {
                b.add(
                    "var %N by %M { %M(%L) }\n",
                    field.name,
                    ComposeNames.remember,
                    ComposeNames.mutableStateOf,
                    sampleValue(field),
                )
            } else {
                b.add("val %N = %L\n", field.name, sampleValue(field))
            }
        }
        return b.build()
    }

    /**
     * The set of writable state field names in [root]'s tree — every [Action.targetPath] across every node's
     * [Node.handlers] (ADR-035, #277), walking children and slots. Drives which [stubs] fields are `var`s. A
     * `Navigate` action contributes nothing (its target is a screen, not state; [targetPath] is null).
     */
    fun writableTargets(root: Node): Set<String> {
        val out = LinkedHashSet<String>()
        fun walk(node: Node) {
            node.handlers.values.forEach { actions -> actions.forEach { it.targetPath?.let(out::add) } }
            node.children.forEach(::walk)
            node.slots.values.forEach { it.forEach(::walk) }
        }
        walk(root)
        return out
    }

    /**
     * A handler slot's body (ADR-035, #277): its [actions] lowered to structural statements, one per line, for a
     * `{ … }` lambda. Every action is a **named, typed, closed** operation built with the KotlinPoet API — no
     * string concatenation (GC-1/GC-2), no evaluation (PF-4): `SetState`→ `f = v`, `Toggle`→ `f = !f`, `Adjust`→
     * `f += by`, `AppendRow`/`RemoveRow`→ list rebuilds, `Navigate`→ a `// TODO` (#214, no nav host yet). Values
     * are literals (typed via the target's declared [ScalarType]) or read [PropValue.StateBinding] member access.
     */
    fun handlerBody(actions: List<Action>, state: List<StateField>): CodeBlock {
        val b = CodeBlock.builder()
        actions.forEach { b.add(lowerAction(it, state)).add("\n") }
        return b.build()
    }

    private fun lowerAction(action: Action, state: List<StateField>): CodeBlock = when (action) {
        is Action.SetState ->
            CodeBlock.of(
                "%N = %L",
                action.target,
                actionValue(action.value, resolveWritableScalar(action.target, state)),
            )

        is Action.Toggle -> CodeBlock.of("%N = !%N", action.target, action.target)

        is Action.Adjust ->
            CodeBlock.of("%N += %L", action.target, actionValue(action.by, resolveWritableScalar(action.target, state)))

        is Action.AppendRow -> {
            val field = resolveWritableTarget(action.target, state)
            val type = (field?.type as? StateType.ListOfRecord)
                ?: throw CodegenException("AppendRow target '${action.target}' is not a list-of-record state field")
            val typeName = recordTypeName(field.name)
            val args = type.fields.map { f ->
                val cell = action.row[f.name]
                    ?: throw CodegenException("AppendRow row for '$typeName' is missing field '${f.name}'")
                CodeBlock.of("%N = %L", f.name, actionValue(cell, (f.type as? StateType.Scalar)?.scalar))
            }
            val element = CodeBlock.of("%L(%L)", typeName, args.joinToCode(", "))
            CodeBlock.of("%N = %N + %L", action.target, action.target, element)
        }

        is Action.RemoveRow ->
            CodeBlock.of(
                "%N = %N.filterIndexed { i, _ -> i != %L }",
                action.target,
                action.target,
                actionValue(action.index, ScalarType.INT),
            )

        // No navigation host is generated yet (#214) — a compilable, honest no-op stub, mirroring ADR-034's
        // seeded-data `// TODO`. Replaced with a real navigation call when screen nav lands.
        is Action.Navigate -> CodeBlock.of("// TODO(#214): navigate to screen %S", action.screenId)
    }

    /**
     * An action value: a [PropValue.Literal] as a typed literal (using the target's [scalar] type so `1`/`1f`/
     * `"x"`/`true` are well-formed), or a read [PropValue.StateBinding] as member access. Any other kind is a
     * codegen error — a handler value is only ever a literal or a read binding (ADR-035).
     */
    private fun actionValue(value: PropValue, scalar: ScalarType?): CodeBlock = when (value) {
        is PropValue.Literal ->
            if (scalar != null) scalarLiteral(scalar, value.value) else untypedLiteral(value.value)
        is PropValue.StateBinding -> CodegenValues.bindingPath(value.path)
        else -> throw CodegenException("Action value must be a literal or a state binding, got $value")
    }

    /** A literal with no declared target type (e.g. an AppendRow row cell of unknown shape): quote strings, emit the rest bare. */
    private fun untypedLiteral(value: JsonPrimitive): CodeBlock =
        if (value.isString) CodeBlock.of("%S", value.content) else CodeBlock.of("%L", value.content)

    /** The generated element type name for a list field: `members` → `Member` (naive singularise + capitalise). */
    fun recordTypeName(fieldName: String): String {
        val base = if (fieldName.length > 1 && fieldName.endsWith("s")) fieldName.dropLast(1) else fieldName
        return base.replaceFirstChar { it.uppercaseChar() }
    }

    private fun dataClass(name: String, fields: List<RecordField>): TypeSpec = TypeSpec.classBuilder(name)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .apply { fields.forEach { addParameter(it.name, fieldType(it)) } }
                .build(),
        )
        .apply {
            fields.forEach { f ->
                addProperty(PropertySpec.builder(f.name, fieldType(f)).initializer("%N", f.name).build())
            }
        }
        .build()

    /** The Kotlin type of a record field: a scalar, or `List<Element>` for a nested list-of-record field (#255). */
    private fun fieldType(field: RecordField): TypeName = when (val t = field.type) {
        is StateType.Scalar -> scalarType(t.scalar)
        is StateType.ListOfRecord -> LIST.parameterizedBy(ClassName("", recordTypeName(field.name)))
    }

    /** The `val`'s initializer: a scalar literal, or `listOf(Type(field = …), …)` for a list-of-record field. */
    private fun sampleValue(field: StateField): CodeBlock = when (val type = field.type) {
        is StateType.Scalar -> scalarLiteral(type.scalar, scalarSample(field))
        is StateType.ListOfRecord -> rowsValue(recordTypeName(field.name), type.fields, rowsSample(field))
    }

    private fun rowsValue(
        typeName: String,
        fields: List<RecordField>,
        rows: List<Map<String, SampleValue>>,
    ): CodeBlock {
        // `listOf` is a default (kotlin.collections) import, so it is emitted as a bare identifier — no import.
        val b = CodeBlock.builder().add("listOf(\n").indent()
        rows.forEach { row -> b.add("%L,\n", record(typeName, fields, row)) }
        return b.unindent().add(")").build()
    }

    /** One record literal: `Member(name = "Ada", role = "Lead")`, args in declared field order (nested → `listOf(…)`). */
    private fun record(typeName: String, fields: List<RecordField>, row: Map<String, SampleValue>): CodeBlock {
        val args = fields.map { f ->
            val cell = row[f.name] ?: throw CodegenException("sample row for '$typeName' is missing field '${f.name}'")
            CodeBlock.of("%N = %L", f.name, cellValue(f, cell))
        }
        return CodeBlock.of("%L(%L)", typeName, args.joinToCode(", "))
    }

    /** A record field's sample: a scalar literal, or `listOf(Element(…), …)` for a nested list-of-record field (#255). */
    private fun cellValue(field: RecordField, cell: SampleValue): CodeBlock = when (val t = field.type) {
        is StateType.Scalar -> {
            val value = cell.scalarValue
                ?: throw CodegenException("scalar field '${field.name}' has a non-scalar sample cell")
            scalarLiteral(t.scalar, value)
        }
        is StateType.ListOfRecord -> {
            val rows = (cell as? SampleValue.Rows)?.rows
                ?: throw CodegenException("nested list field '${field.name}' has a non-rows sample cell")
            rowsValue(recordTypeName(field.name), t.fields, rows)
        }
    }

    private fun scalarLiteral(type: ScalarType, value: JsonPrimitive): CodeBlock = when (type) {
        ScalarType.STRING -> CodeBlock.of("%S", value.content)
        ScalarType.INT ->
            CodeBlock.of(
                "%L",
                value.intOrNull ?: throw CodegenException("state sample expects an Int, got '${value.content}'"),
            )
        ScalarType.FLOAT ->
            CodeBlock.of(
                "%Lf",
                value.floatOrNull ?: throw CodegenException("state sample expects a Float, got '${value.content}'"),
            )
        ScalarType.BOOL ->
            CodeBlock.of(
                "%L",
                value.booleanOrNull ?: throw CodegenException("state sample expects a Bool, got '${value.content}'"),
            )
    }

    private fun scalarType(type: ScalarType): TypeName = when (type) {
        ScalarType.STRING -> STRING
        ScalarType.INT -> INT
        ScalarType.FLOAT -> FLOAT
        ScalarType.BOOL -> BOOLEAN
    }

    private fun scalarSample(field: StateField): JsonPrimitive = (field.sample as? SampleValue.Scalar)?.value
        ?: throw CodegenException("scalar state '${field.name}' has no scalar sample")

    private fun rowsSample(field: StateField): List<Map<String, SampleValue>> =
        (field.sample as? SampleValue.Rows)?.rows
            ?: throw CodegenException("list state '${field.name}' has no rows sample")
}

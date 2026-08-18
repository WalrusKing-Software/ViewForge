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
import viewforge.model.RecordField
import viewforge.model.SampleValue
import viewforge.model.ScalarType
import viewforge.model.StateField
import viewforge.model.StateType
import viewforge.model.scalarValue

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
     * The seeded state block: a `// TODO` line then one `val <name> = <sample>` per field, or null when the
     * screen has no state (so a stateless screen's body is byte-identical to before). Prepended to the body.
     */
    fun stubs(state: List<StateField>): CodeBlock? {
        if (state.isEmpty()) return null
        val b = CodeBlock.builder()
        b.add("// TODO: replace with your real data source\n")
        state.forEach { field -> b.add("val %N = %L\n", field.name, sampleValue(field)) }
        return b.build()
    }

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

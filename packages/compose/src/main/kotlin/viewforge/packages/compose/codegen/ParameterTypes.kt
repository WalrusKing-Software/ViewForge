package viewforge.packages.compose.codegen

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import viewforge.model.PropValue
import viewforge.model.Theme

/**
 * The single source of truth for a component [viewforge.model.Parameter]'s `type` string (parameters
 * slice 2, ADR-028): it maps each supported type to both the KotlinPoet [TypeName] used in the
 * generated function signature and the emitter for an argument value at a call site. Keeping the two
 * in one place means the declaration and the call can never disagree about a type.
 *
 * The set is the literal-derived types the extract flow will produce (a parameter is lifted from a
 * literal-valued prop). An unsupported type fails loudly with [CodegenException] rather than emitting
 * a signature that will not compile — codegen is never the first place an error surfaces silently.
 */
internal object ParameterTypes {
    /** The parameter types codegen can lower. Shared by [signatureType] and [argValue]. */
    val SUPPORTED: Set<String> = setOf("String", "Int", "Float", "Boolean", "Dp", "Color")

    /** The KotlinPoet type for a parameter of [type], for the generated function signature. */
    fun signatureType(type: String): TypeName = when (type) {
        "String" -> STRING
        "Int" -> INT
        "Float" -> FLOAT
        "Boolean" -> BOOLEAN
        "Dp" -> ComposeNames.Dp
        "Color" -> ComposeNames.Color
        else -> throw CodegenException("Unsupported component parameter type '$type'")
    }

    /**
     * Emits an argument [value] for a parameter of [type] at a call site (or a parameter default),
     * reusing the same value emitters screens use so a literal renders identically everywhere.
     */
    fun argValue(type: String, value: PropValue?, theme: Theme): CodeBlock = when (type) {
        "String" -> CodegenValues.text(value)
        "Int" -> CodegenValues.int(value)
        "Float" -> CodegenValues.float(value)
        "Boolean" -> CodegenValues.bool(value)
        "Dp" -> CodegenValues.dpProp(value)
        "Color" -> CodegenValues.color(value, theme)
        else -> throw CodegenException("Unsupported component parameter type '$type'")
    }
}

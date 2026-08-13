package viewforge.model

/**
 * Maps between a [PropType] (what the inspector edits) and the type string stored in a [Parameter.type]
 * (ADR-028). This is the framework-agnostic half of the parameter type contract; the Compose package
 * maps the same strings to KotlinPoet types on the codegen side, so both must encode the same set.
 *
 * Only value-like types are representable as a parameter — a prop of one of these can be promoted to a
 * parameter, and an instance's argument of that type can be edited in the inspector. Enum, Typography,
 * Shape, and Resource are intentionally excluded in Phase 1 (they have no plain Kotlin-type name a
 * parameter can carry yet).
 */
object ParameterType {
    // PropType -> the Kotlin type name a Parameter.type carries. Keep in sync with the Compose package's
    // ParameterTypes (codegen), which lowers these same strings to KotlinPoet TypeNames.
    private val NAME_BY_TYPE: Map<PropType, String> = mapOf(
        PropType.String to "String",
        PropType.Int to "Int",
        PropType.Float to "Float",
        PropType.Bool to "Boolean",
        PropType.Dp to "Dp",
        PropType.Color to "Color",
    )
    private val TYPE_BY_NAME: Map<String, PropType> = NAME_BY_TYPE.entries.associate { (type, name) -> name to type }

    /** The parameter type string for [type], or null when [type] cannot be a parameter (e.g. Enum). */
    fun nameFor(type: PropType): String? = NAME_BY_TYPE[type]

    /** The inspector control [PropType] for a parameter whose type string is [name], or null if unknown. */
    fun propTypeFor(name: String): PropType? = TYPE_BY_NAME[name]

    /** Whether a prop of [type] can be promoted to a parameter (its type is representable). */
    fun isPromotable(type: PropType): Boolean = type in NAME_BY_TYPE
}

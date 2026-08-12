package viewforge.packages.compose.codegen

/** Raised when the IR cannot be turned into valid Kotlin — surfaced before anything is written. */
class CodegenException(message: String) : RuntimeException(message)

/**
 * GC-3: identifiers derived from user input (screen/component names) must be legal Kotlin
 * identifiers and must not collide with a hard keyword, or the generated file won't compile. This is
 * the last line of defence — names are also validated at edit time — so it fails loudly rather than
 * emitting `fun class(...)`.
 */
object KotlinIdentifiers {
    // Kotlin's *hard* keywords: reserved everywhere, so a function named one of these is illegal.
    // Soft/modifier keywords (e.g. `data`, `inline`) are contextual and legal as function names, so
    // they are deliberately not listed.
    private val HARD_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
        "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    // A Kotlin identifier (without backticks): letter/underscore start, then letters/digits/underscore.
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun isValidFunctionName(name: String): Boolean = IDENTIFIER.matches(name) && name !in HARD_KEYWORDS

    /**
     * Returns [name] if it is a legal function identifier, else throws with a specific reason (GC-3).
     * Used for both screen and user-component names — both become `@Composable fun` names.
     */
    fun requireFunctionName(name: String): String {
        if (name.isBlank()) throw CodegenException("Name is blank; cannot be a composable function name")
        if (!IDENTIFIER.matches(name)) {
            throw CodegenException("Name '$name' is not a legal Kotlin identifier (GC-3)")
        }
        if (name in HARD_KEYWORDS) {
            throw CodegenException("Name '$name' is a reserved Kotlin keyword (GC-3)")
        }
        return name
    }
}

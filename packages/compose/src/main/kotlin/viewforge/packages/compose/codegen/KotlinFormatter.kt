package viewforge.packages.compose.codegen

/**
 * The G7 formatting pass (ARCHITECTURE §7 step 4, ADR-018): a final normalization over generated
 * source so the output is *idiomatic*, not merely valid. KotlinPoet already manages imports, 4-space
 * indentation, and line wrapping — the one thing it will not do is omit the default `public`
 * visibility modifier, which it emits explicitly with no toggle (ADR-018). Idiomatic Kotlin leaves
 * `public` implicit, so this pass strips it.
 *
 * Deliberately a small, deterministic string transform rather than an embedded ktlint engine
 * (ADR-019): the only normalization M6's emitter actually needs is the `public` removal, and a
 * targeted pass keeps the output golden-testable with no new runtime dependency (SECURITY DS-6). If a
 * richer formatter is ever required, it replaces the body of [format] behind this same seam.
 */
object KotlinFormatter {
    // A leading `public ` is only ever a redundant visibility modifier when it precedes a Kotlin
    // declaration keyword. Matching the keyword (rather than a bare leading `public `) means a prop
    // value that happens to begin a line with the word "public" can never be rewritten — the emitter
    // indents such content past column 0 anyway, but this keeps the transform provably structural.
    private val REDUNDANT_PUBLIC =
        Regex(
            """^(\s*)public (?=(?:fun|val|var|class|object|interface|enum|sealed|abstract|annotation|data|open|suspend|inline|external|companion)\b)""",
        )

    /** Returns [source] with idiomatic normalizations applied. Idempotent. */
    fun format(source: String): String = source.lineSequence()
        .joinToString("\n") { line -> REDUNDANT_PUBLIC.replace(line, "$1") }
}

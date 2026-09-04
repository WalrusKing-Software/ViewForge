package viewforge.model

/**
 * How much attention an [Advisory] warrants. [WARNING] is a likely problem the user should probably fix
 * (an accessibility gap); [INFO] is a gentle hint. Deliberately coarse — advisories are non-blocking
 * guidance, never a gate, so a fine-grained scale would imply an enforcement they don't have.
 */
enum class AdvisorySeverity { INFO, WARNING }

/**
 * An actionable repair the inspector can offer inline alongside an [Advisory] (#385). Framework-neutral
 * data only — the inspector maps each variant to the appropriate [EditorState] call, so `core` stays
 * free of UI dependencies.
 */
sealed class AdvisoryFix {
    /** Remove the responsive override for [propName] at [breakpointId], reverting it to the base value. */
    data class ClearResponsiveOverride(val breakpointId: String, val propName: String) : AdvisoryFix()
}

/**
 * A **non-blocking** editor advisory about a node (#315): an accessibility or UX hint surfaced in the
 * inspector so it fails loud *there*, never at codegen or load. Distinct from `core/project`'s
 * `ProjectValidator`, which *throws* on a structural/safety violation of an untrusted file — an advisory is
 * only guidance the user is free to ignore, so it is a plain value, not an exception.
 *
 * Framework-neutral: `core` defines the shape, but *which* advisories a node earns is framework-specific
 * (Material's 48dp touch target, Compose's `contentDescription`), so the concrete package produces them behind
 * the editor's catalog seam — the same split as the prop schema.
 *
 * [fixes] carries zero or more [AdvisoryFix] actions the inspector renders as inline repair links (#385).
 * An empty list means the advisory has no automated repair.
 */
data class Advisory(
    val message: String,
    val severity: AdvisorySeverity = AdvisorySeverity.WARNING,
    val fixes: List<AdvisoryFix> = emptyList(),
)

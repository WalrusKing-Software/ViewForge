package viewforge.spi

/**
 * One **build target** of a framework package (ARCHITECTURE §6.2, ADR-008): a platform a project can be
 * exported to — desktop, Android, and later iOS/web. A package owns a shared component model plus N
 * targets; only export routing and (later) preview profiles differ between them.
 *
 * `core` names no framework: a target is an opaque [id] plus a rule for which KMP **source set** should
 * own each [GeneratedFile] in a multi-target export ([sourceSetFor], the G9 routing seam). The one
 * package (Compose, ADR-007) supplies the desktop and Android targets in `packages/compose`.
 *
 * A target owns its export routing ([sourceSetFor]) and its **device preview profiles**
 * ([previewProfiles], #220/M12): the canvas viewport sizes a screen can be framed to, including — for
 * Android — real device density and system-bar insets (ADR-026 Phase-2 amendment). This mirrors ADR-037,
 * where the Android target likewise owns its device-specific numbers (window-size-class thresholds), and
 * lands only now that #220 gives the editor a consumer for it (ADR-007 — not ahead of one).
 */
interface TargetDefinition {
    /** Stable target id: `"desktop"` | `"android"` (later `"ios"` | `"web"`). */
    val id: String

    /**
     * The device preview profiles this target contributes to the editor's frame selector ([PreviewProfile]).
     * The editor aggregates every target's list; a profile is framework-neutral data (viewport dp, density,
     * insets) resolved against a screen's persisted `previewProfile` id (ADR-026). Preview-only — never
     * affects codegen.
     */
    val previewProfiles: List<PreviewProfile>

    /**
     * The KMP source set that should own [file] in a multi-target export: `"commonMain"` for shared UI,
     * or a per-target set (`"jvmMain"`, `"androidMain"`) for a platform entry point. A target recognises
     * its own entry-point files and routes everything else to `commonMain`, so the shared screens are
     * written once and each platform contributes only its entry point (ADR-036).
     */
    fun sourceSetFor(file: GeneratedFile): String
}

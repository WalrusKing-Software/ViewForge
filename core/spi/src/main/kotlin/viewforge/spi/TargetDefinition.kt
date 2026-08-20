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
 * Kept deliberately minimal — only what the source-set-aware exporter (#218/M11) needs. Preview profiles
 * (the ARCHITECTURE §6.2 sketch's `previewProfiles`, ADR-026) attach when the Android device frames land
 * (#220/M12); adding them here now would generalize the SPI ahead of a consumer (ADR-007).
 */
interface TargetDefinition {
    /** Stable target id: `"desktop"` | `"android"` (later `"ios"` | `"web"`). */
    val id: String

    /**
     * The KMP source set that should own [file] in a multi-target export: `"commonMain"` for shared UI,
     * or a per-target set (`"jvmMain"`, `"androidMain"`) for a platform entry point. A target recognises
     * its own entry-point files and routes everything else to `commonMain`, so the shared screens are
     * written once and each platform contributes only its entry point (ADR-036).
     */
    fun sourceSetFor(file: GeneratedFile): String
}

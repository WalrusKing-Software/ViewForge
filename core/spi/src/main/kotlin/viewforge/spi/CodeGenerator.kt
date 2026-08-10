package viewforge.spi

import viewforge.model.Project

/**
 * One generated source artifact: a project-relative [path] and its [content]. Kept deliberately
 * flat — no source-set routing or target metadata yet. Per-target output (`commonMain` vs
 * `androidMain`, G9) arrives with the Android phase; adding it now would be generalizing the SPI for
 * a framework that doesn't exist (ADR-007).
 */
data class GeneratedFile(val path: String, val content: String)

/**
 * The code-generation half of the SPI (ARCHITECTURE §6.2). A framework package turns a [Project] into
 * ready-to-write Kotlin source. `core` defines the seam but knows no framework specifics: the one
 * implementation ([viewforge.packages.compose] `ComposeCodeGenerator`) lives in `packages/compose`
 * and is the only one until Phase 5 (ADR-007).
 *
 * Emission must use a structural source model (KotlinPoet), never string concatenation — this is a
 * security requirement, not a style one (SECURITY GC-1/GC-2, CLAUDE.md rule 4).
 */
interface CodeGenerator {
    /**
     * Generates one file per screen. Pure: no disk access — writing goes through the guarded writer
     * (CLAUDE.md rule 6). Throws on structurally invalid input (e.g. an illegal screen name, GC-3)
     * rather than emitting code that won't compile.
     */
    fun generate(project: Project): List<GeneratedFile>
}

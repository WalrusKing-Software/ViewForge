package viewforge.editor.state

import viewforge.model.Project
import java.nio.file.Path

/** Which shape of output an export produces (G4 vs G5). */
enum class ExportMode {
    /** Just the generated `@Composable` screen files, for pasting into an existing project (G4). */
    LOOSE_FILES,

    /** A complete runnable Gradle Compose Desktop project scaffold (G5). */
    GRADLE_PROJECT,
}

/**
 * The result of previewing or running a safe regeneration into an owned directory (G10). Plain path lists
 * so the seam stays Compose- and `core/project`-free; `:app` maps the exporter's outcome onto this.
 *
 * A non-empty [blocked] means the regeneration is refused (those files are user-authored and would be
 * overwritten): after a real [ProjectExportService.regenerate] with blocks, [written]/[deleted] are empty
 * and nothing changed on disk. Otherwise [written] were (re)generated and [deleted] were removed as orphans.
 */
data class RegenerationReport(val written: List<String>, val deleted: List<String>, val blocked: List<String>)

/**
 * The editor-owned export seam (the ADR-013 pattern, like [ComponentCatalog]): the shell triggers an
 * export through this interface without ever naming `packages/compose`. `:app` binds it to the Compose
 * desktop target exporter and the guarded writer in `core/project`.
 *
 * Compose-free (this module has no UI-kit dependency): it deals only in the [Project], a destination
 * [Path], and relative-path strings.
 */
interface ProjectExportService {
    /**
     * The relative paths under [dir] that exporting [project] in [mode] would overwrite, so the shell
     * can ask for confirmation before replacing anything (FW-5). Never writes.
     */
    fun conflicts(project: Project, dir: Path, mode: ExportMode): List<String>

    /**
     * Writes the export of [project] into [dir] and returns the relative paths written. Throws if a
     * destination is unsafe (the guarded writer rejects it) — the caller surfaces that to the user.
     */
    fun export(project: Project, dir: Path, mode: ExportMode): List<String>

    /**
     * A dry-run of a safe regeneration of the managed Gradle project into [dir] (G10): what would be
     * written, which orphaned owned files would be deleted, and which unowned files would block it. Never
     * writes — the shell shows this before applying.
     */
    fun regenerationReport(project: Project, dir: Path): RegenerationReport

    /**
     * Safely regenerate the managed Gradle project into the owned directory [dir] (G10). Replaces ViewForge's
     * own prior output and removes its orphans, but refuses (writing nothing) if it would overwrite a file it
     * does not own — the returned [RegenerationReport.blocked] then lists them.
     */
    fun regenerate(project: Project, dir: Path): RegenerationReport
}

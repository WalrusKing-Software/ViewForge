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
}

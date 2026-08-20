package viewforge.editor.shell

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import viewforge.editor.state.EditorState
import viewforge.editor.state.ExportMode
import viewforge.editor.state.ProjectExportService
import java.nio.file.Path
import javax.swing.JFileChooser

/**
 * The in-editor export flow (M7): two actions — loose `.kt` files (G4) and a full runnable Gradle
 * project (G5) — each picking a destination directory, confirming any overwrite (FW-5), and reporting
 * the result. Everything runs through the Compose-free [ProjectExportService] seam, so the shell never
 * names the framework package (ADR-013).
 *
 * Hoisted into a controller (remembered once in the shell) rather than living inside a single button,
 * so the toolbar *and* the File menu (#19) trigger the very same flow — the menu stays a thin view with
 * no duplicated logic. The confirm/result dialogs are rendered once from the shell via [ExportDialogs].
 *
 * Export runs synchronously on the UI thread: a project is a handful of small files, so the brief block
 * is unnoticeable. Moving codegen/IO to `Dispatchers.IO` (ARCHITECTURE §8) is a worthwhile refinement
 * once export grows (large trees, many screens), not needed for this minimal surface.
 */
internal class ExportController(private val state: EditorState, private val service: ProjectExportService) {
    var pending by mutableStateOf<PendingExport?>(null)
        private set
    var result by mutableStateOf<String?>(null)
        private set

    /** A regeneration awaiting confirmation because it would delete orphaned files (G10). */
    var pendingRegen by mutableStateOf<PendingRegen?>(null)
        private set

    /** The unowned files that blocked a regeneration (G10); shown so the user can resolve them. */
    var blockedRegen by mutableStateOf<List<String>?>(null)
        private set

    /** Pick a directory and export, deferring to an overwrite confirmation when files would clash. */
    fun start(mode: ExportMode) {
        val dir = chooseDirectory("Export ${state.document.name}", state.defaultExportPath) ?: return
        runCatching { service.conflicts(state.document, dir, mode) }
            .fold(
                onSuccess = { conflicts ->
                    if (conflicts.isEmpty()) write(mode, dir) else pending = PendingExport(mode, dir, conflicts)
                },
                onFailure = { e -> result = "Export failed: ${e.message}" },
            )
    }

    fun confirmOverwrite() {
        val p = pending ?: return
        pending = null
        write(p.mode, p.dir)
    }

    fun dismissConfirm() {
        pending = null
    }

    fun dismissResult() {
        result = null
    }

    /**
     * Safely regenerate the managed Gradle project into a chosen directory (G10). A dry-run decides the UX:
     * unowned files that would be clobbered raise a refusal; orphan deletions raise a confirmation; an
     * otherwise-clean run applies straight away.
     */
    fun regenerate() {
        val dir = chooseDirectory("Regenerate ${state.document.name}", state.defaultExportPath) ?: return
        val report = runCatching { service.regenerationReport(state.document, dir) }
            .getOrElse { e ->
                result = "Regeneration failed: ${e.message}"
                return
            }
        when {
            report.blocked.isNotEmpty() -> blockedRegen = report.blocked
            report.deleted.isNotEmpty() -> pendingRegen = PendingRegen(dir, report.written.size, report.deleted)
            else -> applyRegen(dir)
        }
    }

    fun confirmRegen() {
        val p = pendingRegen ?: return
        pendingRegen = null
        applyRegen(p.dir)
    }

    fun dismissRegen() {
        pendingRegen = null
    }

    fun dismissBlockedRegen() {
        blockedRegen = null
    }

    private fun applyRegen(dir: Path) {
        val report = runCatching { service.regenerate(state.document, dir) }
            .getOrElse { e ->
                result = "Regeneration failed: ${e.message}"
                return
            }
        result = if (report.blocked.isNotEmpty()) {
            "Regeneration refused — these files are not managed by ViewForge:\n\n" + report.blocked.joinToString("\n")
        } else {
            val removed = if (report.deleted.isNotEmpty()) ", removed ${report.deleted.size} orphaned file(s)" else ""
            "Regenerated ${report.written.size} file(s)$removed in $dir"
        }
    }

    private fun write(mode: ExportMode, dir: Path) {
        result = runCatching { service.export(state.document, dir, mode) }
            .fold(
                onSuccess = { written -> "Exported ${written.size} file(s) to $dir" },
                onFailure = { e -> "Export failed: ${e.message}" },
            )
    }
}

@Composable
internal fun rememberExportController(state: EditorState, service: ProjectExportService): ExportController =
    remember(state, service) { ExportController(state, service) }

/** The toolbar's export buttons — thin triggers over [controller]. */
@Composable
internal fun ExportBar(controller: ExportController) {
    ToolbarButton("Export .kt", enabled = true) { controller.start(ExportMode.LOOSE_FILES) }
    ToolbarButton("Export Project", enabled = true) { controller.start(ExportMode.GRADLE_PROJECT) }
    ToolbarButton("Export Multiplatform Project", enabled = true) {
        controller.start(ExportMode.MULTIPLATFORM_PROJECT)
    }
}

/** The overwrite-confirmation and result dialogs, rendered once at the shell root. */
@Composable
internal fun ExportDialogs(controller: ExportController) {
    controller.pending?.let { pending ->
        AlertDialog(
            onDismissRequest = controller::dismissConfirm,
            title = { Text("Overwrite ${pending.conflicts.size} file(s)?") },
            text = {
                Text(
                    "These files already exist under the chosen directory and will be replaced:\n\n" +
                        pending.conflicts.joinToString("\n"),
                )
            },
            confirmButton = { TextButton(onClick = controller::confirmOverwrite) { Text("Overwrite") } },
            dismissButton = { TextButton(onClick = controller::dismissConfirm) { Text("Cancel") } },
        )
    }

    controller.pendingRegen?.let { pending ->
        val orphans = pending.orphans
        AlertDialog(
            onDismissRequest = controller::dismissRegen,
            title = { Text("Regenerate project?") },
            text = {
                Text(
                    "This will (re)write ${pending.writeCount} generated file(s) and delete " +
                        "${orphans.size} orphaned file(s) ViewForge previously generated:\n\n" +
                        orphans.joinToString("\n"),
                )
            },
            confirmButton = { TextButton(onClick = controller::confirmRegen) { Text("Regenerate") } },
            dismissButton = { TextButton(onClick = controller::dismissRegen) { Text("Cancel") } },
        )
    }

    controller.blockedRegen?.let { unowned ->
        AlertDialog(
            onDismissRequest = controller::dismissBlockedRegen,
            title = { Text("Regeneration blocked") },
            text = {
                Text(
                    "These files in the target directory are not managed by ViewForge and will not be " +
                        "overwritten. Move or remove them, or choose another directory:\n\n" +
                        unowned.joinToString("\n"),
                )
            },
            confirmButton = { TextButton(onClick = controller::dismissBlockedRegen) { Text("OK") } },
        )
    }

    controller.result?.let { message ->
        AlertDialog(
            onDismissRequest = controller::dismissResult,
            title = { Text("Export") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = controller::dismissResult) { Text("OK") } },
        )
    }
}

internal data class PendingExport(val mode: ExportMode, val dir: Path, val conflicts: List<String>)

/** A regeneration awaiting confirmation (G10): [orphans] would be deleted and [writeCount] files (re)written. */
internal data class PendingRegen(val dir: Path, val writeCount: Int, val orphans: List<String>)

/**
 * A native directory picker. Runs modally on the calling (UI) thread — standard for a file dialog.
 * Returns null when the user cancels. Opens in [startIn] (the S5 default-export-path preference, #105) when
 * that is a non-blank, existing directory; otherwise it starts wherever the OS chooses.
 */
private fun chooseDirectory(title: String, startIn: String = ""): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        startIn.trim().takeIf { it.isNotEmpty() }
            ?.let { runCatching { java.io.File(it).takeIf(java.io.File::isDirectory) }.getOrNull() }
            ?.let { currentDirectory = it }
    }
    return if (chooser.showDialog(null, "Export here") == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()
    } else {
        null
    }
}

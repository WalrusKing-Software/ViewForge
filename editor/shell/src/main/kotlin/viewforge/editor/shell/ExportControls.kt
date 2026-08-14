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

/** The toolbar's two export buttons — a thin trigger over [controller]. */
@Composable
internal fun ExportBar(controller: ExportController) {
    ToolbarButton("Export .kt", enabled = true) { controller.start(ExportMode.LOOSE_FILES) }
    ToolbarButton("Export Project", enabled = true) { controller.start(ExportMode.GRADLE_PROJECT) }
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

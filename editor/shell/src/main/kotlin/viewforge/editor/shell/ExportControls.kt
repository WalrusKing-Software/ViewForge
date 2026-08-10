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
 * The minimal in-editor export surface (M7): two toolbar actions — loose `.kt` files (G4) and a full
 * runnable Gradle project (G5) — each picking a destination directory, confirming any overwrite
 * (FW-5), and reporting the result. It drives everything through the Compose-free [ProjectExportService]
 * seam, so the shell never names the framework package (ADR-013).
 *
 * Export runs synchronously on the UI thread here: a project is a handful of small files, so the brief
 * block is unnoticeable. Moving codegen/IO to `Dispatchers.IO` (ARCHITECTURE §8) is a worthwhile
 * refinement once export grows (large trees, many screens), not needed for this minimal surface.
 */
@Composable
internal fun ExportBar(state: EditorState, service: ProjectExportService) {
    var confirm by remember { mutableStateOf<PendingExport?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    fun write(mode: ExportMode, dir: Path) {
        result = runCatching { service.export(state.document, dir, mode) }
            .fold(
                onSuccess = { written -> "Exported ${written.size} file(s) to $dir" },
                onFailure = { e -> "Export failed: ${e.message}" },
            )
    }

    fun start(mode: ExportMode) {
        val dir = chooseDirectory("Export ${state.document.name}") ?: return
        runCatching { service.conflicts(state.document, dir, mode) }
            .fold(
                onSuccess = { conflicts ->
                    if (conflicts.isEmpty()) write(mode, dir) else confirm = PendingExport(mode, dir, conflicts)
                },
                onFailure = { e -> result = "Export failed: ${e.message}" },
            )
    }

    ToolbarButton("Export .kt", enabled = true) { start(ExportMode.LOOSE_FILES) }
    ToolbarButton("Export Project", enabled = true) { start(ExportMode.GRADLE_PROJECT) }

    confirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Overwrite ${pending.conflicts.size} file(s)?") },
            text = {
                Text(
                    "These files already exist under the chosen directory and will be replaced:\n\n" +
                        pending.conflicts.joinToString("\n"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = pending
                    confirm = null
                    write(p.mode, p.dir)
                }) { Text("Overwrite") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    result?.let { message ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text("Export") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { result = null }) { Text("OK") } },
        )
    }
}

private data class PendingExport(val mode: ExportMode, val dir: Path, val conflicts: List<String>)

/**
 * A native directory picker. Runs modally on the calling (UI) thread — standard for a file dialog.
 * Returns null when the user cancels.
 */
private fun chooseDirectory(title: String): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showDialog(null, "Export here") == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()
    } else {
        null
    }
}

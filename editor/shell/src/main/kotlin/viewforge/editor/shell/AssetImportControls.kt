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
import viewforge.editor.state.ImageImportRequest
import viewforge.model.Asset
import viewforge.model.Ulid
import viewforge.project.GuardedWriter
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The image-import flow (#141, ADR-021): copies a picked file from disk into the project's sidecar
 * `assets/` directory, records it as an [Asset], and points the requesting node's resource prop at it —
 * all in one undoable step (via [EditorState.commitImageImport]). Hoisted into a controller and
 * remembered once in the shell, exactly like [DocumentController]: the inspector only *requests* an
 * import (setting [EditorState.imageImportRequest]); the disk work — the native file dialog, the guarded
 * byte copy, the dimension decode — lives here, so the panels stay Compose- and disk-free.
 *
 * **Requires a saved project.** Assets are copied *beside* the `.vforge` file (DATA_MODEL §9), so there
 * has to be a file to sit beside: a never-saved document has nowhere portable to put the bytes, so the
 * import is refused with a notice rather than staging into a temp dir that a later Save As would strand.
 *
 * Runs synchronously on the UI thread like [DocumentController] — the file copy and decode are quick and
 * the chooser is a modal that pumps its own event loop.
 */
internal class AssetImportController(private val state: EditorState) {
    /** A message to surface (import refused, or a copy/decode error), or null. Success is silent. */
    var notice: String? by mutableStateOf(null)
        private set

    /**
     * Handle one image-import [request]: refuse (with a notice) when the project is unsaved, otherwise pick
     * a file, copy it into `assets/`, and bind it to the node. A cancelled chooser is a silent no-op. The
     * caller clears [EditorState.imageImportRequest] after this returns.
     */
    fun handle(request: ImageImportRequest) {
        // Require a saved project so the sidecar assets/ dir has a stable home next to the .vforge file.
        val projectDir = state.currentPath?.parent
        if (projectDir == null) {
            notice = "Save the project before importing images. Images are copied into an \"assets\" folder " +
                "next to the .vforge file, so the project needs to be saved first."
            return
        }

        val source = chooseImageFile() ?: return
        try {
            // Validate + normalize the untrusted file BEFORE it touches the project (SECURITY §7): size and
            // pixel caps (AS-1), content type-sniff (AS-2), metadata strip via re-encode (AS-5).
            val vetted = when (val result = AssetImport.validateAndNormalize(Files.readAllBytes(source))) {
                is AssetImport.Result.Rejected -> {
                    notice = result.reason
                    return
                }
                is AssetImport.Result.Ok -> result
            }

            val assetsDir = projectDir.resolve(ASSETS_DIR)
            // The stored name keeps the original stem but takes the sniffed extension, so a mislabeled file
            // is corrected (AS-2) rather than stored under a lying name.
            val originalName = source.fileName?.toString() ?: "image"
            val fileName = uniqueAssetName(assetsDir, withExtension(originalName, vetted.extension))
            // Guarded, atomic, root-confined copy (CLAUDE.md rule 6, PF-5/FW-2): the destination must
            // resolve inside the project dir, so nothing escapes via a crafted name.
            GuardedWriter.writeBytes(assetsDir.resolve(fileName), vetted.bytes, root = projectDir)

            val asset = Asset(
                id = "asset_${Ulid.next()}",
                type = "image",
                // Always project-relative and forward-slashed (DATA_MODEL §9) so the file stays portable.
                path = "$ASSETS_DIR/$fileName",
                originalName = originalName,
                width = vetted.width,
                height = vetted.height,
            )
            state.commitImageImport(request.nodeId, request.propName, asset)
        } catch (e: Exception) {
            notice = "Could not import the image: ${e.message}"
        }
    }

    fun dismissNotice() {
        notice = null
    }
}

/** The project-relative sidecar directory imported assets are copied into (DATA_MODEL §9). */
internal const val ASSETS_DIR = "assets"

/**
 * A safe, non-colliding file name for [original] within [assetsDir]: sanitized to a portable name, then
 * suffixed `-2`, `-3`, … if a file of that name already exists, so an import never overwrites an existing
 * asset (human-readable dedupe rather than a content hash).
 */
internal fun uniqueAssetName(assetsDir: Path, original: String): String {
    val safe = sanitizeFileName(original)
    val dot = safe.lastIndexOf('.')
    val stem = if (dot > 0) safe.substring(0, dot) else safe
    val ext = if (dot > 0) safe.substring(dot) else ""
    var candidate = safe
    var n = 2
    while (Files.exists(assetsDir.resolve(candidate))) {
        candidate = "$stem-$n$ext"
        n++
    }
    return candidate
}

/**
 * Reduce [name] to a portable file name: strip any directory parts, map anything outside `[A-Za-z0-9._-]`
 * to `_`, and trim leading/trailing dots/spaces/underscores (so no hidden file, no trailing dot that
 * [GuardedWriter] would reject). Falls back to "image" if nothing usable remains.
 */
internal fun sanitizeFileName(name: String): String {
    val base = runCatching { Path.of(name).fileName?.toString() }.getOrNull() ?: name
    val mapped = base.map { c -> if (c.isLetterOrDigit() || c in "._-") c else '_' }.joinToString("")
    return mapped.trim('.', ' ', '_').ifBlank { "image" }
}

/** Replace [name]'s extension with [extension] (or append one when it has none), preserving the stem. */
internal fun withExtension(name: String, extension: String): String {
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    return "$stem.$extension"
}

/** Native open dialog restricted to image files. Returns null when the user cancels. */
private fun chooseImageFile(): Path? {
    val extensions = AssetImport.ACCEPTED_EXTENSIONS.toTypedArray()
    val chooser = JFileChooser().apply {
        dialogTitle = "Import image"
        fileSelectionMode = JFileChooser.FILES_ONLY
        isAcceptAllFileFilterUsed = false
        fileFilter = FileNameExtensionFilter("Images (${extensions.joinToString(", ")})", *extensions)
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
}

@Composable
internal fun rememberAssetImportController(state: EditorState): AssetImportController =
    remember(state) { AssetImportController(state) }

/** The import-refused / import-error notice, rendered once at the shell root (like [DocumentDialogs]). */
@Composable
internal fun AssetImportDialogs(controller: AssetImportController) {
    controller.notice?.let { message ->
        AlertDialog(
            onDismissRequest = controller::dismissNotice,
            title = { Text("Couldn't import the image") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = controller::dismissNotice) { Text("OK") } },
        )
    }
}

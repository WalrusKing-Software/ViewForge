package viewforge.editor.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import viewforge.editor.state.EditorState
import viewforge.prefs.EditorPreferences
import javax.swing.JFileChooser

/**
 * The in-app Preferences dialog (S5, #105): edits the persisted editor settings — autosave cadence, undo
 * depth, and the default export directory. Rendered once at the shell root like the other modals.
 *
 * It writes through the shell's [PreferencesController], so each Save load-merges into `preferences.json`
 * (one facet never clobbers another, per #88) and takes effect live where feasible: the autosave interval
 * re-drives the recovery timer, and a lowered undo depth trims history immediately.
 *
 * Invalid entries are rejected inline (I8): the numeric fields show an error and **Save is disabled** while
 * any is invalid, so a bad value is never written. Fields are seeded from the current live values each time
 * the dialog opens.
 */
@Composable
internal fun PreferencesDialog(state: EditorState, prefs: PreferencesController, onDismiss: () -> Unit) {
    var autosave by remember { mutableStateOf(state.autosaveIntervalSeconds.toString()) }
    var historyDepth by remember { mutableStateOf(state.historyDepth.toString()) }
    var exportPath by remember { mutableStateOf(state.defaultExportPath) }

    val autosaveError = autosaveIntervalError(autosave)
    val historyError = historyDepthError(historyDepth)
    val canSave = autosaveError == null && historyError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Preferences") },
        text = {
            Column {
                OutlinedTextField(
                    value = autosave,
                    onValueChange = { autosave = it },
                    label = { Text("Autosave interval (seconds)") },
                    singleLine = true,
                    isError = autosaveError != null,
                    supportingText = {
                        Text(
                            autosaveError ?: "How often unsaved work is autosaved for crash recovery " +
                                "(${EditorPreferences.MIN_AUTOSAVE_INTERVAL_SECONDS}–" +
                                "${EditorPreferences.MAX_AUTOSAVE_INTERVAL_SECONDS}).",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(
                    value = historyDepth,
                    onValueChange = { historyDepth = it },
                    label = { Text("Undo history depth") },
                    singleLine = true,
                    isError = historyError != null,
                    supportingText = {
                        Text(
                            historyError ?: "How many undo steps to keep " +
                                "(${EditorPreferences.MIN_HISTORY_DEPTH}–" +
                                "${EditorPreferences.MAX_HISTORY_DEPTH}).",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(4.dp))
                Row {
                    OutlinedTextField(
                        value = exportPath,
                        onValueChange = { exportPath = it },
                        label = { Text("Default export directory") },
                        singleLine = true,
                        supportingText = { Text("Where Export opens by default. Leave blank for no default.") },
                        modifier = Modifier.fillMaxWidth(0.75f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { chooseExportDirectory(exportPath)?.let { exportPath = it } }) {
                        Text("Browse…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    // Safe: canSave guarantees both parse and sit in range; the setters clamp again defensively.
                    prefs.setAutosaveInterval(autosave.trim().toInt())
                    prefs.setHistoryDepth(historyDepth.trim().toInt())
                    prefs.setDefaultExportPath(exportPath)
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Inline validation (I8) for the autosave field: null when valid, else a short reason. Pure — unit-tested. */
internal fun autosaveIntervalError(raw: String): String? = intRangeError(
    raw,
    EditorPreferences.MIN_AUTOSAVE_INTERVAL_SECONDS,
    EditorPreferences.MAX_AUTOSAVE_INTERVAL_SECONDS,
)

/** Inline validation (I8) for the undo-depth field: null when valid, else a short reason. Pure — unit-tested. */
internal fun historyDepthError(raw: String): String? =
    intRangeError(raw, EditorPreferences.MIN_HISTORY_DEPTH, EditorPreferences.MAX_HISTORY_DEPTH)

/**
 * The shared numeric-field rule: the trimmed text must be a whole number within [[min], [max]]. Returns the
 * error message to show under the field, or null when the value is acceptable. Kept pure so the dialog's
 * accept/reject logic is testable without a composition.
 */
internal fun intRangeError(raw: String, min: Int, max: Int): String? {
    val value = raw.trim().toIntOrNull() ?: return "Enter a whole number."
    return if (value < min || value > max) "Must be between $min and $max." else null
}

/**
 * A native directory picker for the default-export-path field, opening in the current value when it is an
 * existing directory. Returns the chosen absolute path, or null when the user cancels.
 */
private fun chooseExportDirectory(startIn: String): String? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Default export directory"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        startIn.trim().takeIf { it.isNotEmpty() }
            ?.let { runCatching { java.io.File(it).takeIf(java.io.File::isDirectory) }.getOrNull() }
            ?.let { currentDirectory = it }
    }
    return if (chooser.showDialog(null, "Use this directory") == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.absolutePath
    } else {
        null
    }
}

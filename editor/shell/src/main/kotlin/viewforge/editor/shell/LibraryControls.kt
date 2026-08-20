package viewforge.editor.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import viewforge.editor.state.EditorState
import viewforge.editor.state.PaletteEntry
import viewforge.model.ChildAddress
import viewforge.model.Ulid
import viewforge.model.reachableComponents
import viewforge.project.ComponentLibraryStore
import viewforge.project.LibraryComponent
import java.nio.file.Path

/**
 * The cross-project component library flow (ADR-033, #209). Owns the on-disk [ComponentLibraryStore] under
 * a per-user config-dir folder ([libraryDir], supplied by `:app`) and keeps [EditorState.libraryComponents]
 * in sync: it (re)loads the folder into the palette and applies add/remove/rename mutations, each a direct
 * store write (not an undoable document command — the library lives outside any `.vforge`, CLAUDE.md rule 3).
 *
 * Hoisted into a controller (remembered once in the shell) like [ExportController]: the palette, the command
 * palette, the Edit menu, and the Manage-Library dialog all drive the same flow. Store I/O runs on the UI
 * thread — a handful of tiny files — and every write is best-effort ([runCatching]): failing to persist a
 * global preference must never interrupt editing (the same posture as [PreferencesController]).
 *
 * Insert routing lives here so both click and command-palette paths agree: a built-in / document-component
 * entry adds directly; a library entry copies into the document, deferring to a name prompt only on a
 * collision ([EditorState.libraryInsertNeedsName]).
 */
internal class LibraryController(private val state: EditorState, private val libraryDir: Path) {
    /** The library entry awaiting a non-colliding name before it is copied in, or null. Drives [LibraryInsertDialog]. */
    var insertPrompt by mutableStateOf<PaletteEntry?>(null)
        private set

    /**
     * Where the prompted insert should land: the resolved drop address for a drag ([dropDrag]), or null for a
     * click (insert at the current selection). Stashed alongside [insertPrompt] so the drop position survives
     * the name dialog — the drag itself is already cleared by the time the dialog opens (#234).
     */
    private var insertTarget: ChildAddress? = null

    /** Whether the Manage-Library dialog is open (Edit ▸ Manage Library…). */
    var showManager by mutableStateOf(false)
        private set

    /** Load the on-disk library into the palette. Called at startup and after every mutation. */
    fun reload() {
        state.applyLibraryComponents(ComponentLibraryStore.list(libraryDir))
    }

    /**
     * Insert palette [entry]: a built-in or document component adds directly; a library entry copies into the
     * document, opening the name prompt only when its name would collide (ADR-033). The one insert entry point
     * shared by the palette click and the command palette.
     */
    fun insert(entry: PaletteEntry) {
        when {
            entry.libraryId == null -> state.addFromPalette(entry)
            state.libraryInsertNeedsName(entry) -> prompt(entry, target = null)
            else -> state.insertLibraryComponent(entry, entry.label)
        }
    }

    /**
     * Commit a drag of library [entry] onto the canvas or Layers tree at the drop position the surfaces
     * resolved ([EditorState.resolvedPaletteDropAddress]) — copy-in directly, or open the name prompt carrying
     * that position when it would collide (#234). Always consumes the in-flight drag. A no-op off a legal
     * target or for a non-library entry (a built-in drag commits through [EditorState.dropPaletteDrag]).
     */
    fun dropDrag(entry: PaletteEntry) {
        val address = state.resolvedPaletteDropAddress
        state.cancelPaletteDrag() // consume the drag; the address is captured above and survives in insertTarget
        if (entry.libraryId == null || address == null) return
        if (state.libraryInsertNeedsName(entry)) {
            prompt(entry, target = address)
        } else {
            state.insertLibraryComponent(entry, entry.label, address)
        }
    }

    /** Open the name prompt for [entry], remembering where the eventual insert should land. */
    private fun prompt(entry: PaletteEntry, target: ChildAddress?) {
        insertTarget = target
        insertPrompt = entry
    }

    /** Confirm the prompted insert with the chosen [name] (a no-op if it is still invalid/duplicate). */
    fun confirmInsert(name: String) {
        val entry = insertPrompt ?: return
        state.insertLibraryComponent(entry, name, insertTarget)
        insertPrompt = null
        insertTarget = null
    }

    fun cancelInsert() {
        insertPrompt = null
        insertTarget = null
    }

    fun openManager() {
        showManager = true
    }

    fun closeManager() {
        showManager = false
    }

    /**
     * Copy the document component [componentId] into the library (ADR-033), bundling its transitive dependency
     * closure so a nested component travels self-contained (#234). Refused only when the closure can't be
     * resolved — a dangling reference ([EditorState.libraryAddBlockReason]). The primary takes a fresh global
     * id and a within-library-unique name; the dependency defs are stored with their origin-document ids
     * intact (the bundle is internally consistent) and remapped on insert.
     */
    fun addToLibrary(componentId: String) {
        if (state.libraryAddBlockReason(componentId) != null) return
        val component = state.document.components.firstOrNull { it.id == componentId } ?: return
        val dependencies = state.document.reachableComponents(componentId) ?: return
        val libId = "lib_${Ulid.next()}"
        val libName = state.uniqueLibraryName(component.name)
        val entry =
            LibraryComponent(component = component.copy(id = libId, name = libName), dependencies = dependencies)
        runCatching { ComponentLibraryStore.save(entry, libraryDir) }
        reload()
    }

    /** Remove library component [libraryId] from the store. */
    fun removeFromLibrary(libraryId: String) {
        runCatching { ComponentLibraryStore.remove(libraryId, libraryDir) }
        reload()
    }

    /** Rename library component [libraryId] to [newName] (a no-op if unknown or the name is invalid/duplicate). */
    fun renameInLibrary(libraryId: String, newName: String) {
        if (state.libraryNameError(newName, excludingId = libraryId) != null) return
        val current = state.libraryComponents.firstOrNull { it.component.id == libraryId } ?: return
        val renamed = current.copy(component = current.component.copy(name = newName.trim()))
        runCatching { ComponentLibraryStore.save(renamed, libraryDir) }
        reload()
    }
}

@Composable
internal fun rememberLibraryController(state: EditorState, libraryDir: Path): LibraryController =
    remember(state, libraryDir) { LibraryController(state, libraryDir) }

/**
 * The name prompt shown when inserting a library component whose name collides with one already in the
 * document (ADR-033). Pre-filled with a suggested free name; Insert stays disabled until the name is a legal,
 * unique document-component identifier ([EditorState.componentNameError]).
 */
@Composable
internal fun LibraryInsertDialog(controller: LibraryController, state: EditorState) {
    val entry = controller.insertPrompt ?: return
    var draft by remember(entry) { mutableStateOf(state.suggestedLibraryName(entry)) }
    val error = state.componentNameError(draft)
    AlertDialog(
        onDismissRequest = controller::cancelInsert,
        title = { Text("Name this component") },
        text = {
            Column {
                Text(
                    "“${entry.label}” already exists in this project. Choose a different name for the copy.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = error == null, onClick = { controller.confirmInsert(draft) }) { Text("Insert") }
        },
        dismissButton = { TextButton(onClick = controller::cancelInsert) { Text("Cancel") } },
    )
}

/**
 * The Manage-Library dialog (ADR-033, #209): the top half lists the global library with rename/remove; the
 * bottom half lists this project's own components, each addable to the library (a component that references
 * others is disabled with a reason — the self-contained-only restriction, #234).
 */
@Composable
internal fun ManageLibraryDialog(controller: LibraryController, state: EditorState) {
    if (!controller.showManager) return
    var renamingId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    val renameError = renamingId?.let { state.libraryNameError(draft, excludingId = it) }

    fun startRename(id: String, current: String) {
        renamingId = id
        draft = current
    }

    fun commitRename() {
        val id = renamingId ?: return
        if (renameError != null) return
        controller.renameInLibrary(id, draft)
        renamingId = null
    }

    AlertDialog(
        onDismissRequest = controller::closeManager,
        title = { Text("Component Library") },
        text = {
            Column(Modifier.width(420.dp).verticalScroll(rememberScrollState())) {
                SectionLabel("Your library")
                if (state.libraryComponents.isEmpty()) {
                    Muted("Your library is empty. Add a component from this project below.")
                } else {
                    state.libraryComponents.forEach { entry ->
                        val component = entry.component
                        if (renamingId == component.id) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                OutlinedTextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    singleLine = true,
                                    isError = renameError != null,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (renameError != null) {
                                    Text(
                                        renameError,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Row {
                                    TextButton(enabled = renameError == null, onClick = ::commitRename) { Text("Save") }
                                    TextButton(onClick = { renamingId = null }) { Text("Cancel") }
                                }
                            }
                        } else {
                            RowItem(component.name) {
                                TextButton(onClick = { startRename(component.id, component.name) }) { Text("Rename") }
                                TextButton(onClick = { controller.removeFromLibrary(component.id) }) { Text("Remove") }
                            }
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                SectionLabel("Add from this project")
                if (state.document.components.isEmpty()) {
                    Muted("This project has no components yet.")
                } else {
                    state.document.components.forEach { component ->
                        val block = state.libraryAddBlockReason(component.id)
                        RowItem(component.name, subtitle = block) {
                            TextButton(enabled = block == null, onClick = { controller.addToLibrary(component.id) }) {
                                Text("Add")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = controller::closeManager) { Text("Close") } },
    )
}

/** One name row with trailing action buttons, and an optional muted [subtitle] (e.g. why an action is disabled). */
@Composable
private fun RowItem(name: String, subtitle: String? = null, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row { actions() }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun Muted(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

package viewforge.editor.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import viewforge.editor.state.EditorState
import kotlin.math.roundToInt

/**
 * The right-click context menu (#160): the common node actions surfaced from the tree and the canvas.
 * The menu itself is owned here in the shell because it is the one module with a material3 menu — the
 * canvas package has no material3 dependency, and a canvas-side menu would also need the click position
 * to hit-test the node, which `ContextMenuArea` does not provide. So both surfaces record a right-click
 * point (and select the target) on [EditorState][viewforge.editor.state.EditorState.requestContextMenu],
 * and [ContextMenuOverlay] renders one menu at that point. Every item routes straight to an existing
 * [EditorState] action — this file holds **no business logic**, only which actions are offered and when.
 */

/**
 * Enabled-state a snapshot of [EditorState] gives the context menu. Extracted as pure data so the
 * item→flag wiring is unit-testable without a composition, exactly like [EditMenuModel].
 *
 * - Cut / Delete / Duplicate need a **non-root** selection: they no-op on the screen (or open component)
 *   root, so a live-but-dead item would only mislead.
 * - Rename routes through the tree's inline editor, so it is offered only while the **tree is visible**.
 * - Extract to Component and Enter Component are contextual (a non-root selection / a component instance)
 *   and are shown only when they apply rather than greyed.
 */
internal data class ContextMenuModel(
    val hasSelection: Boolean,
    val canCutDelete: Boolean,
    val canDuplicate: Boolean,
    val canPaste: Boolean,
    val canRename: Boolean,
    val canExtract: Boolean,
    val canEnterComponent: Boolean,
)

internal fun EditorState.contextMenuModel(): ContextMenuModel {
    val hasSelection = selectedNode != null
    // Compare against the root *node's* id (activeEditRootId is the command dispatch key — the screen or
    // component id — not the node id), so cut/delete/duplicate are withheld on the edit surface's root.
    val notRoot = hasSelection && selectedId != activeEditRoot?.id
    return ContextMenuModel(
        hasSelection = hasSelection,
        canCutDelete = notRoot,
        canDuplicate = notRoot,
        canPaste = canPaste,
        canRename = hasSelection && treeVisible,
        canExtract = canExtractSelection,
        canEnterComponent = selectedNode?.let { componentOfInstance(it) != null } ?: false,
    )
}

/**
 * Renders the context menu at the window-space point the tree/canvas recorded, or nothing when it is
 * closed. Hosted at the shell root (a sibling of the editor content) so the recorded window coordinates
 * line up with a zero-size anchor offset from the same origin; the `DropdownMenu` opens at that anchor
 * and re-fits itself to stay on-screen. Choosing an item runs its action and dismisses the menu.
 */
@Composable
internal fun ContextMenuOverlay(state: EditorState) {
    val x = state.contextMenuX ?: return
    val y = state.contextMenuY ?: return
    val model = state.contextMenuModel()
    Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }) {
        DropdownMenu(expanded = true, onDismissRequest = state::dismissContextMenu) {
            ContextItem("Cut", model.canCutDelete, state) { state.cut() }
            ContextItem("Copy", model.hasSelection, state) { state.copySelected() }
            ContextItem("Paste", model.canPaste, state) { state.paste() }
            ContextItem("Duplicate", model.canDuplicate, state) { state.duplicateSelected() }
            HorizontalDivider()
            ContextItem("Delete", model.canCutDelete, state) { state.deleteSelected() }
            if (model.canRename || model.canExtract || model.canEnterComponent) {
                HorizontalDivider()
                if (model.canRename) {
                    ContextItem("Rename", true, state) { state.requestRenameSelected() }
                }
                if (model.canExtract) {
                    ContextItem("Extract to Component", true, state) {
                        state.extractSelectionToComponent(state.uniqueComponentName())
                    }
                }
                if (model.canEnterComponent) {
                    ContextItem("Enter Component", true, state) {
                        state.selectedNode?.let { state.openInstanceComponent(it) }
                    }
                }
            }
        }
    }
}

/** A single context-menu item: greyed when [enabled] is false; running it always closes the menu. */
@Composable
private fun ContextItem(label: String, enabled: Boolean, state: EditorState, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        enabled = enabled,
        onClick = {
            onClick()
            state.dismissContextMenu()
        },
    )
}

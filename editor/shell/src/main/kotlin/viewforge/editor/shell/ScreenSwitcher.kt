@file:OptIn(ExperimentalFoundationApi::class)

package viewforge.editor.shell

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import viewforge.editor.state.EditorState

/**
 * The screen switcher (FEATURES §5 D6): a horizontal strip of the document's screens that switches the
 * active screen, renames a screen inline, and adds/removes screens. Each screen's name becomes its
 * generated composable/file name, so names are validated here at **edit time** (GC-3 + uniqueness) —
 * a bad name is refused with visible feedback rather than only blowing up at export.
 *
 * All three mutations go through [EditorState] (and thus commands / undo, rule 3). Renaming is a
 * controlled edit — the draft text lives here so the validity check and its message can be shown live
 * while typing, and the commit is gated on that check.
 */
@Composable
fun ScreenSwitcher(state: EditorState, modifier: Modifier = Modifier) {
    // The screen currently being renamed (null = none), and the live draft + its validation error.
    var renamingId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    val error = renamingId?.let { state.screenNameError(draft, excludingId = it) }

    fun startRename(id: String, current: String) {
        state.activeScreenId = id
        draft = current
        renamingId = id
    }

    fun cancelRename() {
        renamingId = null
    }

    fun commitRename() {
        val id = renamingId ?: return
        if (error != null) return // stay in edit mode until the name is valid
        state.renameScreen(id, draft)
        renamingId = null
    }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val screens = state.document.screens
            screens.forEach { screen ->
                if (renamingId == screen.id) {
                    ScreenNameField(
                        value = draft,
                        isError = error != null,
                        onValueChange = { draft = it },
                        onCommit = ::commitRename,
                        onCancel = ::cancelRename,
                    )
                } else {
                    // Right-click a tab to publish that screen as a reusable palette component (#184) —
                    // acts on the tab's own screen id, so it works without switching to it first.
                    ContextMenuArea(
                        items = {
                            listOf(
                                ContextMenuItem("Save Screen as Component") {
                                    state.saveScreenAsComponent(
                                        screen.id,
                                        state.defaultComponentNameForScreen(screen.id),
                                    )
                                },
                            )
                        },
                    ) {
                        ScreenTab(
                            name = screen.name,
                            active = screen.id == state.activeScreenId,
                            canClose = screens.size > 1,
                            onSelect = { state.activeScreenId = screen.id },
                            onRename = { startRename(screen.id, screen.name) },
                            onClose = { state.removeScreen(screen.id) },
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
            }

            // Add a new, uniquely-named screen and make it active (D6).
            Text(
                text = "+",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = state::addScreen)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )

            // Live validation feedback for the in-progress rename, so a bad name fails loudly here.
            if (error != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** One screen tab: click to switch, double-click to rename, a × to remove (when more than one screen). */
@Composable
private fun ScreenTab(
    name: String,
    active: Boolean,
    canClose: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
) {
    val background = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val content = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .combinedClickable(onClick = onSelect, onDoubleClick = onRename)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (active && canClose) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "×",
                style = MaterialTheme.typography.labelMedium,
                color = content,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 2.dp),
            )
        }
    }
}

/**
 * The inline rename editor for a screen tab. Auto-focuses; Enter commits (rejected while [isError]),
 * Escape cancels, and losing focus commits — matching the tree's node-rename affordance. The border
 * turns to the error color while the name is invalid or a duplicate.
 */
@Composable
private fun ScreenNameField(
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var everFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val borderColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .focusRequester(focus)
            .onFocusChanged { st ->
                if (st.isFocused) {
                    everFocused = true
                } else if (everFocused) {
                    onCommit()
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter -> {
                        onCommit()
                        true
                    }
                    Key.Escape -> {
                        onCancel()
                        true
                    }
                    else -> false
                }
            },
    )
}

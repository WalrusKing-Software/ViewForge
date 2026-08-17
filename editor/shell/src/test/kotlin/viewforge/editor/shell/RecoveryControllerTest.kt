package viewforge.editor.shell

import viewforge.editor.state.ComponentCatalog
import viewforge.editor.state.EditorState
import viewforge.editor.state.PaletteEntry
import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import viewforge.project.RecoverySnapshot
import viewforge.project.RecoveryStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RecoveryController.clearIfClean] must uphold the never-lose-work guarantee (D4): drop the recovery
 * sidecar the instant the document is clean (so a Save-and-quit leaves no stale restore offer, #189), but
 * never while the document is dirty or while a recovered snapshot is still awaiting the user's answer. The
 * store round-trip itself is [viewforge.project.RecoveryStore]'s own test; this pins the guard.
 */
class RecoveryControllerTest {
    private val dir: Path = Files.createTempDirectory("vforge-recovery-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.foundation.layout.Column", "Column", "Layout"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = true

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private fun freshState(): EditorState {
        val root = Node(
            id = NodeId("root"),
            type = "compose.foundation.layout.Column",
            children = listOf(Node(NodeId("a"), "compose.material3.Text")),
        )
        return EditorState(
            Project(
                id = "p",
                name = "P",
                framework = FrameworkRef("compose-multiplatform", "1.0.0"),
                screens = listOf(Screen("s1", "Home", root)),
            ),
            FakeCatalog(),
        )
    }

    private fun writeSidecar(state: EditorState) =
        RecoveryStore.save(RecoverySnapshot(originalPath = null, savedAt = 0L, document = state.document), dir)

    private fun sidecarExists(): Boolean = RecoveryStore.load(dir) != null

    @Test
    fun `clearIfClean drops the sidecar when the document is clean and nothing is pending`() {
        val state = freshState() // no snapshot on disk at construction -> pending == null; a fresh doc is clean
        val recovery = RecoveryController(state, dir)
        writeSidecar(state) // a stale snapshot left by an earlier dirty tick
        assertTrue(sidecarExists())

        recovery.clearIfClean()

        assertFalse(sidecarExists(), "a cleanly-saved document must leave no restore offer")
    }

    @Test
    fun `clearIfClean keeps the sidecar while the document is dirty`() {
        val state = freshState()
        val recovery = RecoveryController(state, dir)
        state.select(NodeId("a"))
        state.deleteSelected() // an unsaved edit
        writeSidecar(state)

        recovery.clearIfClean()

        assertTrue(sidecarExists(), "unsaved work must stay recoverable")
    }

    @Test
    fun `clearIfClean keeps a pending recovery even when the document is clean`() {
        writeSidecar(freshState()) // snapshot exists BEFORE construction -> loaded as pending
        val recovery = RecoveryController(freshState(), dir)

        recovery.clearIfClean()

        assertTrue(sidecarExists(), "must not clear the recovery the user has not yet answered")
    }
}

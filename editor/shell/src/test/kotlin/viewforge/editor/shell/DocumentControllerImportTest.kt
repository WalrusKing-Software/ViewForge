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
import viewforge.project.ImportFailure
import viewforge.project.ImportResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The #227 "Open Generated" glue is a thin view over [viewforge.project.ProjectImporter] (whose recognition
 * is its own test), so what needs pinning is the pure shell mapping: every [ImportFailure] gets a distinct,
 * fail-loud diagnostic, and a success opens the reconstructed document as *untitled* while activating the
 * manifest-mapped screen (falling back to the first when that mapping is stale). No composition or chooser
 * needed — the logic is exercised through [DocumentController.applyImportResult] and [importedScreenSelection].
 */
class DocumentControllerImportTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.foundation.layout.Column", "Column", "Layout"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = true

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private fun screen(id: String, name: String) =
        Screen(id, name, Node(NodeId.random(), "compose.foundation.layout.Column"))

    /** A two-screen project distinct from whatever the editor is currently showing. */
    private fun imported(): Project = Project(
        id = "imported",
        name = "Imported",
        framework = FrameworkRef("compose-multiplatform", "1.0.0"),
        screens = listOf(screen("s1", "Home"), screen("s2", "Detail")),
    )

    private fun freshState(): EditorState = EditorState(
        Project(
            id = "open",
            name = "Open",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(screen("existing", "Existing")),
        ),
        FakeCatalog(),
    )

    private fun controller(state: EditorState) = DocumentController(state, PreferencesController(state))

    @Test
    fun `every import failure kind gets a distinct non-blank diagnostic carrying the detail`() {
        val controller = controller(freshState())
        val messages = ImportFailure.entries.associateWith { kind ->
            controller.describeImport(ImportResult.Failure(kind, detail = "detail for $kind"))
        }
        messages.forEach { (kind, message) ->
            assertTrue(message.isNotBlank(), "$kind must have a message")
            assertTrue("detail for $kind" in message, "$kind must surface the importer's detail")
        }
        assertEquals(
            ImportFailure.entries.size,
            messages.values.map { it.substringBefore("\n\n") }.toSet().size,
            "each failure kind must read differently",
        )
    }

    @Test
    fun `a successful import opens the reconstructed project as an untitled clean document`() {
        val state = freshState()
        val project = imported()

        controller(state).applyImportResult(ImportResult.Success(project, screenId = null))

        assertEquals(project, state.document)
        assertNull(state.currentPath, "a re-opened .kt is an entry point, not a .vforge save target")
        assertTrue(!state.isDirty)
    }

    @Test
    fun `a successful import activates the manifest-mapped screen`() {
        val state = freshState()
        val project = imported()

        controller(state).applyImportResult(ImportResult.Success(project, screenId = "s2"))

        assertEquals("s2", state.activeScreenId)
    }

    @Test
    fun `a successful import falls back to the first screen when the mapped screen is missing`() {
        val state = freshState()
        val project = imported()

        controller(state).applyImportResult(ImportResult.Success(project, screenId = "gone"))

        assertEquals("s1", state.activeScreenId, "a stale mapping must not point at a screen that isn't there")
    }

    @Test
    fun `an import failure surfaces a diagnostic and leaves the open document untouched`() {
        val state = freshState()
        val before = state.document
        val controller = controller(state)

        controller.applyImportResult(ImportResult.Failure(ImportFailure.NOT_GENERATED, detail = "no header"))

        assertEquals(before, state.document, "a refused import must not swap the open document")
        assertTrue(controller.error?.isNotBlank() == true)
    }

    @Test
    fun `importedScreenSelection keeps a live mapping, drops a stale one, and passes through null`() {
        val project = imported()
        assertEquals("s2", importedScreenSelection(project, "s2"))
        assertNull(importedScreenSelection(project, "gone"))
        assertNull(importedScreenSelection(project, null))
    }
}

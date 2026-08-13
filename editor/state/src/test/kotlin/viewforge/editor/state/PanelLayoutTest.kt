package viewforge.editor.state

import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import viewforge.prefs.PanelLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Panel width state and layout persistence bridge (S1, #43). Widths are transient chrome held on
 * [EditorState]; the resize drag clamps them, and [EditorState.applyLayout]/[EditorState.panelLayout]
 * carry them to and from the persisted [PanelLayout].
 */
class PanelLayoutTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.material3.Text", "Text", "Content"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = false

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private fun state(): EditorState = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(Screen("s1", "Home", Node(NodeId("root"), "compose.material3.Text"))),
        ),
        FakeCatalog(),
    )

    @Test
    fun `panels start at the default widths`() {
        val s = state()
        assertEquals(PanelLayout.DEFAULT_PALETTE_WIDTH, s.paletteWidth)
        assertEquals(PanelLayout.DEFAULT_TREE_WIDTH, s.treeWidth)
        assertEquals(PanelLayout.DEFAULT_INSPECTOR_WIDTH, s.inspectorWidth)
    }

    @Test
    fun `resize adds the delta to the panel width`() {
        val s = state()
        s.resizePalette(20f)
        assertEquals(PanelLayout.DEFAULT_PALETTE_WIDTH + 20f, s.paletteWidth)
    }

    @Test
    fun `resize clamps to the width bounds`() {
        val s = state()
        s.resizeTree(-100000f)
        assertEquals(PanelLayout.MIN_WIDTH, s.treeWidth)
        s.resizeInspector(100000f)
        assertEquals(PanelLayout.MAX_WIDTH, s.inspectorWidth)
    }

    @Test
    fun `applyLayout restores visibility and clamped widths`() {
        val s = state()
        s.applyLayout(
            PanelLayout(
                paletteVisible = false,
                treeVisible = true,
                inspectorVisible = false,
                codePreviewVisible = true,
                paletteWidth = 250f,
                treeWidth = 99999f, // out of range: must clamp
                inspectorWidth = 300f,
                codePreviewWidth = 360f,
            ),
        )
        assertFalse(s.paletteVisible)
        assertTrue(s.treeVisible)
        assertFalse(s.inspectorVisible)
        assertTrue(s.codePreviewVisible)
        assertEquals(250f, s.paletteWidth)
        assertEquals(PanelLayout.MAX_WIDTH, s.treeWidth)
        assertEquals(300f, s.inspectorWidth)
        assertEquals(360f, s.codePreviewWidth)
    }

    @Test
    fun `panelLayout snapshots the current visibility and widths`() {
        val s = state()
        s.togglePalette()
        s.toggleCodePreview()
        s.resizeInspector(15f)
        s.resizeCodePreview(-20f)
        val snapshot = s.panelLayout()
        assertFalse(snapshot.paletteVisible)
        assertTrue(snapshot.treeVisible)
        assertTrue(snapshot.codePreviewVisible)
        assertEquals(PanelLayout.DEFAULT_INSPECTOR_WIDTH + 15f, snapshot.inspectorWidth)
        assertEquals(PanelLayout.DEFAULT_CODE_PREVIEW_WIDTH - 20f, snapshot.codePreviewWidth)
    }

    @Test
    fun `applyLayout then panelLayout is a faithful round-trip`() {
        val s = state()
        val layout = PanelLayout(
            paletteVisible = true,
            treeVisible = false,
            inspectorVisible = true,
            codePreviewVisible = true,
            paletteWidth = 190f,
            treeWidth = 240f,
            inspectorWidth = 260f,
            codePreviewWidth = 320f,
        )
        s.applyLayout(layout)
        assertEquals(layout, s.panelLayout())
    }
}

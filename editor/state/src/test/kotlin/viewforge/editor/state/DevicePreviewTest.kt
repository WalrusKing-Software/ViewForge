package viewforge.editor.state

import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Device preview frames (C6): the pure [DeviceProfiles] registry resolution, and the [EditorState] side
 * — the active screen's profile resolves (defaulting when absent/unknown) and setting it is an undoable
 * command. The dropdown gesture and the canvas frame are UI, out of scope here as with the other state
 * tests.
 */
class DevicePreviewTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.foundation.layout.Column", "Column", "Layout"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = true

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private fun state(previewProfile: String?): EditorState = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(
                Screen("s1", "Home", Node(NodeId("root"), "compose.foundation.layout.Column"), previewProfile),
            ),
        ),
        FakeCatalog(),
    )

    @Test
    fun `forId returns the matching profile, else the default`() {
        assertEquals("desktop_1440x900", DeviceProfiles.forId("desktop_1440x900").id)
        assertEquals(DeviceProfiles.DEFAULT, DeviceProfiles.forId(null))
        assertEquals(DeviceProfiles.DEFAULT, DeviceProfiles.forId("nonsense"))
    }

    @Test
    fun `forId parses a custom id back into its dimensions`() {
        val id = DeviceProfiles.customProfileId(1000, 1400)
        assertEquals("custom_1000x1400", id)
        val profile = DeviceProfiles.forId(id)
        assertEquals(id, profile.id)
        assertEquals(1000f, profile.width)
        assertEquals(1400f, profile.height)
    }

    @Test
    fun `forId resolves any dimension-encoding id, e_g_ a newer build's preset`() {
        val profile = DeviceProfiles.forId("tablet_800x1280")
        assertEquals(800f, profile.width)
        assertEquals(1280f, profile.height)
    }

    @Test
    fun `forId clamps out-of-range custom dimensions`() {
        val tooSmall = DeviceProfiles.forId("custom_1x1")
        assertEquals(DeviceProfiles.MIN_DIMENSION.toFloat(), tooSmall.width)
        assertEquals(DeviceProfiles.MIN_DIMENSION.toFloat(), tooSmall.height)

        val tooBig = DeviceProfiles.forId("custom_99999x99999")
        assertEquals(DeviceProfiles.MAX_DIMENSION.toFloat(), tooBig.width)
        assertEquals(DeviceProfiles.MAX_DIMENSION.toFloat(), tooBig.height)
    }

    @Test
    fun `a named preset wins over dimension parsing`() {
        // desktop_1280x800 is a registry entry, so it resolves to that exact profile, not a synthesized one.
        assertEquals(DeviceProfiles.ALL.first { it.id == "desktop_1280x800" }, DeviceProfiles.forId("desktop_1280x800"))
    }

    @Test
    fun `setPreviewProfile accepts a custom size and undoes back`() {
        val s = state(null)
        s.setPreviewProfile(DeviceProfiles.customProfileId(1000, 1400))
        assertEquals(1000f, s.activeDeviceProfile.width)
        assertEquals(1400f, s.activeDeviceProfile.height)

        s.undo()
        assertEquals(DeviceProfiles.DEFAULT, s.activeDeviceProfile)
    }

    @Test
    fun `activeDeviceProfile resolves the active screen's profile and defaults otherwise`() {
        assertEquals("desktop_1920x1080", state("desktop_1920x1080").activeDeviceProfile.id)
        assertEquals(DeviceProfiles.DEFAULT, state(null).activeDeviceProfile)
        assertEquals(DeviceProfiles.DEFAULT, state("gone").activeDeviceProfile)
    }

    @Test
    fun `setPreviewProfile updates the screen and undoes back`() {
        val s = state(null)
        s.setPreviewProfile("desktop_1440x900")
        assertEquals("desktop_1440x900", s.activeScreen?.previewProfile)
        assertEquals("desktop_1440x900", s.activeDeviceProfile.id)

        s.undo()
        assertEquals(null, s.activeScreen?.previewProfile)
        assertEquals(DeviceProfiles.DEFAULT, s.activeDeviceProfile)
    }

    @Test
    fun `setPreviewProfile to the current value is a no-op (no history entry)`() {
        val s = state("desktop_1280x800")
        s.setPreviewProfile("desktop_1280x800")
        assertEquals(false, s.canUndo)
    }

    @Test
    fun `fitToFrame sizes the zoom to the active profile within the given area`() {
        // 1920x1080 profile at density 1 in a 960x1080 area: width binds -> 960/1920 = 0.5.
        val s = state("desktop_1920x1080")
        s.fitToFrame(availW = 960f, availH = 1080f, density = 1f)
        assertEquals(0.5f, s.viewport.zoom)
        assertEquals(0f, s.viewport.panX)
        assertEquals(0f, s.viewport.panY)
    }

    @Test
    fun `no-arg fitToFrame uses the recorded bounds and is a no-op until measured`() {
        val s = state("desktop_1920x1080")
        s.fitToFrame() // nothing measured yet
        assertEquals(CanvasViewport(), s.viewport)

        s.canvasFitBounds = CanvasFitBounds(availW = 960f, availH = 1080f, density = 1f)
        s.fitToFrame()
        assertEquals(0.5f, s.viewport.zoom)
    }

    @Test
    fun `canFitToFrame needs both an edit root and a measured canvas`() {
        val s = state(null)
        assertEquals(false, s.canFitToFrame) // has a screen root, but no measured area yet
        s.canvasFitBounds = CanvasFitBounds(800f, 600f, 1f)
        assertEquals(true, s.canFitToFrame)
    }
}

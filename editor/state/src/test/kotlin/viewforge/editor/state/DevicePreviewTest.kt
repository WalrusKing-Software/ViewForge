package viewforge.editor.state

import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.Screen
import viewforge.spi.PreviewInsets
import viewforge.spi.PreviewProfile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Device preview frames (C6, ADR-026, #220): the [DeviceProfiles] resolution against the injected profile
 * list, and the [EditorState] side — the active screen's profile resolves (defaulting when absent/unknown),
 * setting it is an undoable command, and an Android profile carries density + insets. The dropdown gesture
 * and the canvas frame/chrome are UI, out of scope here as with the other state tests.
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

    // A stand-in for what the app injects (DesktopTarget + AndroidTarget profiles) — the test names no
    // framework package, so it defines its own fixtures with the same shape.
    private val profiles = listOf(
        PreviewProfile("desktop_1024x768", "Desktop 1024 × 768", 1024f, 768f, group = "Desktop"),
        PreviewProfile("desktop_1280x800", "Desktop 1280 × 800", 1280f, 800f, group = "Desktop"),
        PreviewProfile("desktop_1440x900", "Desktop 1440 × 900", 1440f, 900f, group = "Desktop"),
        PreviewProfile("desktop_1920x1080", "Desktop 1920 × 1080", 1920f, 1080f, group = "Desktop"),
        PreviewProfile(
            "android_phone_393x851",
            "Pixel 5 (393 × 851)",
            393f,
            851f,
            density = 2.75f,
            insets = PreviewInsets(top = 24f, bottom = 48f),
            group = "Android Phone",
        ),
    )

    private val default = DeviceProfiles.defaultProfile(profiles)

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
        previewProfiles = profiles,
    )

    @Test
    fun `forId returns the matching profile, else the default`() {
        assertEquals("desktop_1440x900", DeviceProfiles.forId("desktop_1440x900", profiles).id)
        assertEquals(default, DeviceProfiles.forId(null, profiles))
        assertEquals(default, DeviceProfiles.forId("nonsense", profiles))
    }

    @Test
    fun `an Android profile resolves with its density and insets`() {
        val p = DeviceProfiles.forId("android_phone_393x851", profiles)
        assertEquals(2.75f, p.density)
        assertEquals(24f, p.insets.top)
        assertEquals(48f, p.insets.bottom)
        assertEquals("Android Phone", p.group)
    }

    @Test
    fun `default and unknown ids fall back to a desktop frame even with no profiles injected`() {
        assertEquals(DeviceProfiles.FALLBACK, DeviceProfiles.forId(null, emptyList()))
        assertEquals(DeviceProfiles.FALLBACK, DeviceProfiles.forId("nonsense", emptyList()))
    }

    @Test
    fun `forId parses a custom id back into its dimensions`() {
        val id = DeviceProfiles.customProfileId(1000, 1400)
        assertEquals("custom_1000x1400", id)
        val profile = DeviceProfiles.forId(id, profiles)
        assertEquals(id, profile.id)
        assertEquals(1000f, profile.width)
        assertEquals(1400f, profile.height)
    }

    @Test
    fun `forId resolves any dimension-encoding id, e_g_ a newer build's preset`() {
        val profile = DeviceProfiles.forId("tablet_800x1280", profiles)
        assertEquals(800f, profile.width)
        assertEquals(1280f, profile.height)
    }

    @Test
    fun `forId clamps out-of-range custom dimensions`() {
        val tooSmall = DeviceProfiles.forId("custom_1x1", profiles)
        assertEquals(DeviceProfiles.MIN_DIMENSION.toFloat(), tooSmall.width)
        assertEquals(DeviceProfiles.MIN_DIMENSION.toFloat(), tooSmall.height)

        val tooBig = DeviceProfiles.forId("custom_99999x99999", profiles)
        assertEquals(DeviceProfiles.MAX_DIMENSION.toFloat(), tooBig.width)
        assertEquals(DeviceProfiles.MAX_DIMENSION.toFloat(), tooBig.height)
    }

    @Test
    fun `a named preset wins over dimension parsing`() {
        // desktop_1280x800 is a registry entry, so it resolves to that exact profile, not a synthesized one.
        assertEquals(profiles.first { it.id == "desktop_1280x800" }, DeviceProfiles.forId("desktop_1280x800", profiles))
    }

    @Test
    fun `setPreviewProfile accepts a custom size and undoes back`() {
        val s = state(null)
        s.setPreviewProfile(DeviceProfiles.customProfileId(1000, 1400))
        assertEquals(1000f, s.activeDeviceProfile.width)
        assertEquals(1400f, s.activeDeviceProfile.height)

        s.undo()
        assertEquals(default, s.activeDeviceProfile)
    }

    @Test
    fun `activeDeviceProfile resolves the active screen's profile and defaults otherwise`() {
        assertEquals("desktop_1920x1080", state("desktop_1920x1080").activeDeviceProfile.id)
        assertEquals(default, state(null).activeDeviceProfile)
        assertEquals(default, state("gone").activeDeviceProfile)
    }

    @Test
    fun `setPreviewProfile updates the screen and undoes back`() {
        val s = state(null)
        s.setPreviewProfile("desktop_1440x900")
        assertEquals("desktop_1440x900", s.activeScreen?.previewProfile)
        assertEquals("desktop_1440x900", s.activeDeviceProfile.id)

        s.undo()
        assertEquals(null, s.activeScreen?.previewProfile)
        assertEquals(default, s.activeDeviceProfile)
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

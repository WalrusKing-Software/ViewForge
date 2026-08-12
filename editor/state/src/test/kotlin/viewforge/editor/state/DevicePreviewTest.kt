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
}

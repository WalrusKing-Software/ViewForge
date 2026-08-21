package viewforge.editor.state

import kotlinx.serialization.json.JsonPrimitive
import viewforge.model.FrameworkRef
import viewforge.model.ModifierDefinition
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropDefinition
import viewforge.model.PropValue
import viewforge.model.Screen
import viewforge.spi.Breakpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The canvas preview breakpoint (#314): [EditorState.previewBreakpoint] resolves the active device frame's
 * width against the injected breakpoints — the render twin of codegen's `BoxWithConstraints` (ADR-037) — so
 * the canvas shows the same per-breakpoint overrides that generate at that width. Base (`compact`) is `null`,
 * labelled "Compact". With no breakpoints injected (headless), it is always the base.
 */
class PreviewBreakpointTest {
    private class FakeCatalog : ComponentCatalog {
        override val palette = listOf(PaletteEntry("compose.foundation.layout.Column", "Column", "Layout"))

        override fun newNode(type: String): Node = Node(NodeId.random(), type)

        override fun acceptsChildren(type: String): Boolean = true

        override fun slotsOf(type: String): List<String> = emptyList()

        override fun propsFor(type: String): List<PropDefinition> = emptyList()

        override val modifierCatalog: List<ModifierDefinition> = emptyList()

        override fun modifierDef(type: String): ModifierDefinition? = null
    }

    private val bps = listOf(Breakpoint("medium", "Medium", 600), Breakpoint("expanded", "Expanded", 840))

    private fun state(frameWidth: Int, breakpoints: List<Breakpoint> = bps): EditorState = EditorState(
        Project(
            id = "p",
            name = "P",
            framework = FrameworkRef("compose-multiplatform", "1.0.0"),
            screens = listOf(
                Screen(
                    "s",
                    "Home",
                    Node(NodeId("root"), "compose.foundation.layout.Column"),
                    DeviceProfiles.customProfileId(frameWidth, 800),
                ),
            ),
        ),
        FakeCatalog(),
        breakpoints = breakpoints,
    )

    @Test
    fun `a narrow frame previews the base breakpoint`() {
        assertNull(state(393).previewBreakpoint)
        assertEquals("Compact", state(393).previewBreakpointLabel)
    }

    @Test
    fun `a medium-width frame previews the medium breakpoint`() {
        assertEquals("medium", state(600).previewBreakpoint)
        assertEquals("Medium", state(700).previewBreakpointLabel)
    }

    @Test
    fun `a wide frame previews the expanded breakpoint`() {
        assertEquals("expanded", state(1280).previewBreakpoint)
        assertEquals("Expanded", state(1280).previewBreakpointLabel)
    }

    @Test
    fun `with no breakpoints injected the preview is always the base`() {
        assertNull(state(1280, breakpoints = emptyList()).previewBreakpoint)
        assertEquals("Compact", state(1280, breakpoints = emptyList()).previewBreakpointLabel)
    }

    // --- edit-what-you-see pin + per-breakpoint editing (#314 slice 2b) --------------------------

    private fun lit(v: Int): PropValue = PropValue.Literal(JsonPrimitive(v))

    @Test
    fun `pinning overrides the frame-derived breakpoint until unpinned`() {
        val s = state(393) // frame → compact (base)
        assertNull(s.previewBreakpoint)
        s.pinBreakpoint("expanded")
        assertTrue(s.isBreakpointPinned)
        assertEquals("expanded", s.previewBreakpoint)
        assertEquals("Expanded", s.previewBreakpointLabel)
        s.unpinBreakpoint()
        assertFalse(s.isBreakpointPinned)
        assertNull(s.previewBreakpoint)
    }

    @Test
    fun `editing targets base props at the base breakpoint and an override at a pinned breakpoint`() {
        val s = state(393)
        val rootId = NodeId("root")
        fun root() = s.document.screens.first().root

        // Base (auto/compact): a plain prop edit.
        s.setPropAtActiveBreakpoint(rootId, "fontSize", lit(14))
        assertEquals(lit(14), root().props["fontSize"])

        // Pinned expanded: an override, leaving the base untouched.
        s.pinBreakpoint("expanded")
        s.setPropAtActiveBreakpoint(rootId, "fontSize", lit(20))
        assertEquals(lit(20), root().responsive["expanded"]?.get("fontSize"))
        assertEquals(lit(14), root().props["fontSize"])

        // effectiveProp / isOverridden reflect the pinned breakpoint.
        assertEquals(lit(20), s.effectiveProp(root(), "fontSize"))
        assertTrue(s.isOverriddenAtActiveBreakpoint(root(), "fontSize"))

        // Clearing the override prunes it back to the base.
        s.setPropAtActiveBreakpoint(rootId, "fontSize", null)
        assertTrue(root().responsive.isEmpty())
        assertFalse(s.isOverriddenAtActiveBreakpoint(root(), "fontSize"))
        assertEquals(lit(14), s.effectiveProp(root(), "fontSize"))
    }
}

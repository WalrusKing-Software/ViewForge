package viewforge.packages.compose.targets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The device preview profiles each Compose target contributes to the editor's frame selector (#220,
 * ADR-026 Phase-2 amendment). The desktop target keeps the plain window sizes (density 1, no chrome); the
 * Android target adds real device frames carrying density and system-bar insets. Ids are self-describing
 * (`<prefix>_<w>x<h>`) so the editor's resolver recovers a size from a forward-compatible id.
 */
class PreviewProfilesTest {
    private val idPattern = Regex("""[a-z_]+_(\d+)x(\d+)""")

    @Test
    fun `desktop profiles are plain windows - density 1, no insets, Desktop group`() {
        assertTrue(DesktopTarget.previewProfiles.isNotEmpty())
        DesktopTarget.previewProfiles.forEach { p ->
            assertEquals(1f, p.density, "desktop density")
            assertTrue(p.insets.isEmpty, "desktop has no system chrome: ${p.id}")
            assertEquals("Desktop", p.group)
            assertTrue(idPattern.matches(p.id), "id encodes its size: ${p.id}")
        }
    }

    @Test
    fun `android profiles carry real density and system-bar insets`() {
        assertTrue(AndroidTarget.previewProfiles.isNotEmpty())
        AndroidTarget.previewProfiles.forEach { p ->
            assertTrue(p.density > 1f, "android density > 1: ${p.id}")
            assertTrue(!p.insets.isEmpty, "android draws inset chrome: ${p.id}")
            assertTrue(p.group.startsWith("Android"), "android group: ${p.group}")
            assertTrue(idPattern.matches(p.id), "id encodes its size: ${p.id}")
        }
    }

    @Test
    fun `Pixel 5 is a 393x851 dp frame at density 2_75 with status and nav insets`() {
        val pixel = AndroidTarget.previewProfiles.single { it.id == "android_phone_393x851" }
        assertEquals(393f, pixel.width)
        assertEquals(851f, pixel.height)
        assertEquals(2.75f, pixel.density)
        assertEquals(24f, pixel.insets.top)
        assertEquals(48f, pixel.insets.bottom)
    }

    @Test
    fun `the package aggregate is desktop then android with unique ids`() {
        assertEquals(
            DesktopTarget.previewProfiles + AndroidTarget.previewProfiles,
            COMPOSE_PREVIEW_PROFILES,
        )
        val ids = COMPOSE_PREVIEW_PROFILES.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "profile ids are unique")
    }
}

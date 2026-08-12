package viewforge.editor.state

/**
 * A selectable canvas viewport size (C6). [width]/[height] are plain dp magnitudes (`Float`) — this
 * module carries only the Compose *runtime*, not the UI unit types, so the canvas attaches `.dp` (the
 * same convention the panel widths use). [id] is the stable token persisted on
 * [viewforge.model.Screen.previewProfile]; [label] is what the selector shows.
 */
data class DeviceProfile(val id: String, val label: String, val width: Float, val height: Float)

/**
 * The Phase-1 device-preview profiles (C6): desktop sizes only, since Phase 1 targets Compose Desktop
 * (FEATURES §1). The canvas frames the active screen to the selected profile and clips to it; the
 * selection is persisted per screen. Ids follow the `desktop_<w>x<h>` convention already used in stored
 * documents (the sample screen carries `desktop_1280x800`).
 */
object DeviceProfiles {
    val ALL: List<DeviceProfile> = listOf(
        DeviceProfile("desktop_1280x800", "Desktop 1280 × 800", 1280f, 800f),
        DeviceProfile("desktop_1440x900", "Desktop 1440 × 900", 1440f, 900f),
        DeviceProfile("desktop_1920x1080", "Desktop 1920 × 1080", 1920f, 1080f),
    )

    /** The fallback profile for a screen with no — or an unrecognized — `previewProfile`. */
    val DEFAULT: DeviceProfile = ALL.first()

    /** The profile for [id], or [DEFAULT] when [id] is null or matches no known profile. */
    fun forId(id: String?): DeviceProfile = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

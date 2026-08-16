package viewforge.editor.state

/**
 * A selectable canvas viewport size (C6). [width]/[height] are plain dp magnitudes (`Float`) — this
 * module carries only the Compose *runtime*, not the UI unit types, so the canvas attaches `.dp` (the
 * same convention the panel widths use). [id] is the stable token persisted on
 * [viewforge.model.Screen.previewProfile]; [label] is what the selector shows.
 */
data class DeviceProfile(val id: String, val label: String, val width: Float, val height: Float)

/**
 * The device-preview profiles (C6). Phase 1 targets Compose Desktop (FEATURES §1), so the named presets
 * are desktop sizes; an arbitrary **custom** size (#163) is expressed as a self-describing id rather than
 * a registry entry (see [customProfileId]). The canvas frames the active screen to the selected profile
 * and clips to it; the selection is persisted per screen. Ids follow the `<prefix>_<w>x<h>` convention
 * already used in stored documents (the sample screen carries `desktop_1280x800`).
 */
object DeviceProfiles {
    /** Clamp bounds for a custom frame (dp): wide enough for any real screen, tight enough to stay usable. */
    const val MIN_DIMENSION: Int = 200
    const val MAX_DIMENSION: Int = 10000

    val ALL: List<DeviceProfile> = listOf(
        DeviceProfile("desktop_1024x768", "Desktop 1024 × 768", 1024f, 768f),
        DeviceProfile("desktop_1280x800", "Desktop 1280 × 800", 1280f, 800f),
        DeviceProfile("desktop_1366x768", "Desktop 1366 × 768", 1366f, 768f),
        DeviceProfile("desktop_1440x900", "Desktop 1440 × 900", 1440f, 900f),
        DeviceProfile("desktop_1600x900", "Desktop 1600 × 900", 1600f, 900f),
        DeviceProfile("desktop_1920x1080", "Desktop 1920 × 1080", 1920f, 1080f),
        DeviceProfile("desktop_2560x1440", "Desktop 2560 × 1440", 2560f, 1440f),
        DeviceProfile("desktop_3840x2160", "Desktop 3840 × 2160", 3840f, 2160f),
    )

    /** The fallback profile for a screen with no — or an unresolvable — `previewProfile`. */
    val DEFAULT: DeviceProfile = ALL.first { it.id == "desktop_1280x800" }

    /** Ids that encode their dimensions, e.g. `desktop_1280x800` or `custom_1000x1400`. */
    private val DIMENSION_ID = Regex("""[a-z]+_(\d+)x(\d+)""")

    /**
     * The id for a custom frame of [width]×[height] dp. The dimensions are encoded *in* the id so a custom
     * size round-trips through [Screen.previewProfile][viewforge.model.Screen.previewProfile] with no schema
     * change and needs no registry entry — [forId] recovers the size by parsing it back.
     */
    fun customProfileId(width: Int, height: Int): String = "custom_${width}x$height"

    /**
     * The profile for [id]. Resolution order: a named preset in [ALL]; else — for any id that encodes its
     * size as `<prefix>_<w>x<h>` (a custom size, or a preset from a newer build) — a profile synthesized
     * from the parsed, clamped dimensions; else [DEFAULT]. So the canvas always has a frame size, and a
     * custom or forward-compatible id resolves to its real dimensions rather than snapping to the default.
     */
    fun forId(id: String?): DeviceProfile {
        if (id == null) return DEFAULT
        ALL.firstOrNull { it.id == id }?.let { return it }
        val match = DIMENSION_ID.matchEntire(id) ?: return DEFAULT
        val w = match.groupValues[1].toIntOrNull()?.coerceIn(MIN_DIMENSION, MAX_DIMENSION) ?: return DEFAULT
        val h = match.groupValues[2].toIntOrNull()?.coerceIn(MIN_DIMENSION, MAX_DIMENSION) ?: return DEFAULT
        return DeviceProfile(id, "Custom $w × $h", w.toFloat(), h.toFloat())
    }
}

package viewforge.editor.state

import viewforge.spi.PreviewProfile

/**
 * Resolution helpers for device-preview frames (C6, ADR-026). The profile **definitions** now live on the
 * framework package's targets ([viewforge.spi.TargetDefinition.previewProfiles]) and are injected into
 * [EditorState] (ADR-026 Phase-2 amendment, #220) — this object only turns a persisted
 * [previewProfile][viewforge.model.Screen.previewProfile] id into a [PreviewProfile] against that injected
 * list, synthesizing a custom/forward-compatible size from a self-describing id and always falling back to
 * a frame so the canvas is never left without a size.
 */
object DeviceProfiles {
    /** Clamp bounds for a custom frame (dp): wide enough for any real screen, tight enough to stay usable. */
    const val MIN_DIMENSION: Int = 200
    const val MAX_DIMENSION: Int = 10000

    /**
     * The last-resort frame when no profiles are injected (headless/tests) and the id is not size-encoding.
     * A plain desktop window, matching the sample document's `desktop_1280x800` so its default is stable.
     */
    val FALLBACK: PreviewProfile = PreviewProfile("desktop_1280x800", "Desktop 1280 × 800", 1280f, 800f)

    /** Ids that encode their dimensions, e.g. `desktop_1280x800`, `custom_1000x1400`, `android_phone_393x851`. */
    private val DIMENSION_ID = Regex("""[a-z_]+_(\d+)x(\d+)""")

    /**
     * The id for a custom frame of [width]×[height] dp. The dimensions are encoded *in* the id so a custom
     * size round-trips through [Screen.previewProfile][viewforge.model.Screen.previewProfile] with no schema
     * change and needs no registry entry — [forId] recovers the size by parsing it back.
     */
    fun customProfileId(width: Int, height: Int): String = "custom_${width}x$height"

    /** The default frame for [available]: the sample's `desktop_1280x800` if present, else the first, else [FALLBACK]. */
    fun defaultProfile(available: List<PreviewProfile>): PreviewProfile =
        available.firstOrNull { it.id == FALLBACK.id } ?: available.firstOrNull() ?: FALLBACK

    /**
     * The profile for [id] among [available]. Resolution order: a named entry in [available]; else — for any
     * id that encodes its size as `<prefix>_<w>x<h>` (a custom size, or a preset from a newer build) — a
     * profile synthesized from the parsed, clamped dimensions; else the [defaultProfile]. So the canvas
     * always has a frame size, and a custom or forward-compatible id resolves to its real dimensions rather
     * than snapping to the default.
     */
    fun forId(id: String?, available: List<PreviewProfile>): PreviewProfile {
        if (id == null) return defaultProfile(available)
        available.firstOrNull { it.id == id }?.let { return it }
        val match = DIMENSION_ID.matchEntire(id) ?: return defaultProfile(available)
        val w =
            match.groupValues[1].toIntOrNull()?.coerceIn(MIN_DIMENSION, MAX_DIMENSION)
                ?: return defaultProfile(available)
        val h =
            match.groupValues[2].toIntOrNull()?.coerceIn(MIN_DIMENSION, MAX_DIMENSION)
                ?: return defaultProfile(available)
        return PreviewProfile(id, "Custom $w × $h", w.toFloat(), h.toFloat())
    }
}

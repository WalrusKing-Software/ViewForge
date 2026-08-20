package viewforge.spi

/**
 * The system-bar / safe-area **insets** of a device preview frame, in dp (ADR-026 Phase-2 amendment,
 * #220). [top] is typically the status bar, [bottom] the navigation bar; [left]/[right] cover a landscape
 * nav bar or a display cutout. All default to 0, so a desktop frame — which has no system chrome — uses
 * [NONE]. Plain dp magnitudes (`Float`), framework-neutral like the rest of this type.
 */
data class PreviewInsets(val top: Float = 0f, val bottom: Float = 0f, val left: Float = 0f, val right: Float = 0f) {
    /** True when there is no chrome to draw (a desktop frame), so the canvas can skip the inset overlay. */
    val isEmpty: Boolean get() = top == 0f && bottom == 0f && left == 0f && right == 0f

    companion object {
        val NONE = PreviewInsets()
    }
}

/**
 * A selectable canvas viewport a screen can be framed to (ADR-026; the ARCHITECTURE §6.2
 * `previewProfiles`). Framework-neutral pure data — no Compose types — so it lives in `core/spi` and each
 * [TargetDefinition] supplies its own profiles: the desktop target its window sizes, the Android target
 * real device frames carrying [density] and [insets] (Phase-2 amendment, #220). The editor resolves a
 * screen's persisted `previewProfile` token to one of these.
 *
 * [width]/[height] are the device's **logical** size in **dp** (for a physical device, its pixel size ÷
 * [density]); the canvas attaches `.dp` (the panel-width convention — `core` names no UI unit types).
 * [density] is px-per-dp, carried for display and so a profile's dp size is derived honestly from a real
 * pixel spec; it does not re-scale rendering (the desktop canvas cannot match a device's text metrics —
 * a within-tolerance preview, TECHNICAL_NOTES §11–12). [group] labels the selector section ("Desktop",
 * "Android Phone", …).
 */
data class PreviewProfile(
    val id: String,
    val label: String,
    val width: Float,
    val height: Float,
    val density: Float = 1f,
    val insets: PreviewInsets = PreviewInsets.NONE,
    val group: String = "Desktop",
)

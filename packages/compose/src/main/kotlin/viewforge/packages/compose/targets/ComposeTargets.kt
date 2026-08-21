package viewforge.packages.compose.targets

import viewforge.spi.GeneratedFile
import viewforge.spi.PreviewInsets
import viewforge.spi.PreviewProfile
import viewforge.spi.TargetDefinition

/**
 * The Compose framework package's [TargetDefinition]s (ADR-008: one package, N targets). Each knows how to
 * route a [GeneratedFile] to the KMP source set that should own it — the G9 seam the [MultiplatformExporter]
 * composes to place shared screens in `commonMain` and each platform's entry point in its own set — and
 * which device [previewProfiles][TargetDefinition.previewProfiles] to contribute to the canvas frame
 * selector (#220). Shared UI (screens, components, `Theme`) is `commonMain` for every target; a target
 * claims only its own entry-point files.
 */

/** Desktop (JVM) target: the `main()` window entry lives in `jvmMain`; everything else is shared. */
object DesktopTarget : TargetDefinition {
    override val id: String = "desktop"

    /** Common desktop window sizes (density 1, no system chrome). Moved here from `editor/state` at #220. */
    override val previewProfiles: List<PreviewProfile> = listOf(
        desktop("desktop_1024x768", 1024, 768),
        desktop("desktop_1280x800", 1280, 800),
        desktop("desktop_1366x768", 1366, 768),
        desktop("desktop_1440x900", 1440, 900),
        desktop("desktop_1600x900", 1600, 900),
        desktop("desktop_1920x1080", 1920, 1080),
        desktop("desktop_2560x1440", 2560, 1440),
        desktop("desktop_3840x2160", 3840, 2160),
    )

    override fun sourceSetFor(file: GeneratedFile): String =
        if (fileName(file.path) == ComposeEntryPoints.MAIN_KT) JVM_MAIN else COMMON_MAIN

    private fun desktop(id: String, w: Int, h: Int): PreviewProfile =
        PreviewProfile(id, "Desktop $w × $h", w.toFloat(), h.toFloat(), density = 1f, group = "Desktop")
}

/**
 * A responsive breakpoint (ADR-037, #222): a model breakpoint [id] (opaque to `core`, ADR-030) mapped to the
 * minimum viewport width in dp at which it applies. Owned by the target — the Compose Android target maps the
 * ids to Material **window size classes** — so codegen's `BoxWithConstraints` branching and (later, #314) the
 * canvas both compare a width against the *same* thresholds. The base ([Node.props][viewforge.model.Node.props])
 * is the `compact` (< the smallest threshold) case and is the final `else`, so it is not listed here.
 */
data class ResponsiveBreakpoint(val id: String, val minWidthDp: Int)

/** Android target: the `MainActivity` and the manifest live in `androidMain`; everything else is shared. */
object AndroidTarget : TargetDefinition {
    /** The Android manifest file name; routed to `androidMain` (placed at the set root, not under `kotlin/`). */
    const val MANIFEST_XML: String = "AndroidManifest.xml"

    override val id: String = "android"

    /**
     * The responsive breakpoints codegen branches on (ADR-037, #222): Material window size classes —
     * `medium` ≥ 600dp and `expanded` ≥ 840dp, with `compact` (< 600dp) being the base/`else`. Largest-first
     * emission is the emitter's job; this is just the id → threshold map the Android target owns.
     */
    val breakpoints: List<ResponsiveBreakpoint> = listOf(
        ResponsiveBreakpoint("medium", 600),
        ResponsiveBreakpoint("expanded", 840),
    )

    /**
     * Real Android device frames (#220/M12). [PreviewProfile.width]/[PreviewProfile.height] are the device's
     * **logical** dp size (physical pixels ÷ density); insets are the status bar (top) and 3-button
     * navigation bar (bottom) in dp — the safe-area chrome the canvas draws so layout under the system bars
     * previews honestly. Sizes span the Material window-size classes (compact < 600dp, medium/expanded) so a
     * responsive layout (#221/#222) can be previewed across breakpoints.
     */
    override val previewProfiles: List<PreviewProfile> = listOf(
        phone("android_phone_360x800", "Compact phone", 360, 800, 3.0f),
        phone("android_phone_393x851", "Pixel 5", 393, 851, 2.75f),
        phone("android_phone_411x914", "Pixel 7", 411, 914, 2.625f),
        PreviewProfile(
            "android_tablet_1280x800",
            "Pixel Tablet",
            1280f,
            800f,
            density = 2.0f,
            insets = PreviewInsets(top = 24f, bottom = 24f),
            group = "Android Tablet",
        ),
    )

    override fun sourceSetFor(file: GeneratedFile): String = when (fileName(file.path)) {
        ComposeEntryPoints.MAIN_ACTIVITY_KT, MANIFEST_XML -> ANDROID_MAIN
        else -> COMMON_MAIN
    }

    private fun phone(id: String, label: String, w: Int, h: Int, density: Float): PreviewProfile = PreviewProfile(
        id,
        "$label ($w × $h)",
        w.toFloat(),
        h.toFloat(),
        density = density,
        insets = PreviewInsets(top = 24f, bottom = 48f),
        group = "Android Phone",
    )
}

/** Every preview profile the Compose package offers the editor (desktop windows + Android devices, #220). */
val COMPOSE_PREVIEW_PROFILES: List<PreviewProfile> = DesktopTarget.previewProfiles + AndroidTarget.previewProfiles

/**
 * The responsive breakpoint id active at a viewport [widthDp] (#314): the largest [breakpoints] entry whose
 * `minWidthDp` the width meets, or `null` for the base/`compact` case below the smallest threshold. This is
 * the render-time twin of codegen's `BoxWithConstraints` branching (ADR-037) — both read a real width and
 * pick a breakpoint against the *same* target thresholds, so the canvas previews exactly what will generate.
 * Width-based (not platform-based), so a wide desktop frame previews the same `expanded` overrides an Android
 * tablet would, matching the generated `BoxWithConstraints` that reads its own width on either target.
 */
fun breakpointForWidth(widthDp: Float, breakpoints: List<ResponsiveBreakpoint> = AndroidTarget.breakpoints): String? =
    breakpoints.filter { widthDp >= it.minWidthDp }.maxByOrNull { it.minWidthDp }?.id

internal const val COMMON_MAIN = "commonMain"
internal const val JVM_MAIN = "jvmMain"
internal const val ANDROID_MAIN = "androidMain"

/** The last path segment, so routing works whether a file is passed bare (`Main.kt`) or with a prefix. */
private fun fileName(path: String): String = path.substringAfterLast('/')

package viewforge.packages.compose.targets

import viewforge.spi.GeneratedFile
import viewforge.spi.TargetDefinition

/**
 * The Compose framework package's [TargetDefinition]s (ADR-008: one package, N targets). Each knows only
 * how to route a [GeneratedFile] to the KMP source set that should own it — the G9 seam the
 * [MultiplatformExporter] composes to place shared screens in `commonMain` and each platform's entry
 * point in its own set. Shared UI (screens, components, `Theme`) is `commonMain` for every target; a
 * target claims only its own entry-point files.
 */

/** Desktop (JVM) target: the `main()` window entry lives in `jvmMain`; everything else is shared. */
object DesktopTarget : TargetDefinition {
    override val id: String = "desktop"

    override fun sourceSetFor(file: GeneratedFile): String =
        if (fileName(file.path) == ComposeEntryPoints.MAIN_KT) JVM_MAIN else COMMON_MAIN
}

/** Android target: the `MainActivity` and the manifest live in `androidMain`; everything else is shared. */
object AndroidTarget : TargetDefinition {
    /** The Android manifest file name; routed to `androidMain` (placed at the set root, not under `kotlin/`). */
    const val MANIFEST_XML: String = "AndroidManifest.xml"

    override val id: String = "android"

    override fun sourceSetFor(file: GeneratedFile): String = when (fileName(file.path)) {
        ComposeEntryPoints.MAIN_ACTIVITY_KT, MANIFEST_XML -> ANDROID_MAIN
        else -> COMMON_MAIN
    }
}

internal const val COMMON_MAIN = "commonMain"
internal const val JVM_MAIN = "jvmMain"
internal const val ANDROID_MAIN = "androidMain"

/** The last path segment, so routing works whether a file is passed bare (`Main.kt`) or with a prefix. */
private fun fileName(path: String): String = path.substringAfterLast('/')

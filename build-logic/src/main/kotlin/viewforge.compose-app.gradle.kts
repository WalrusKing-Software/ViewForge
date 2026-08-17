// Convention for the desktop entry point (:app). A Compose-aware module with a runnable
// application. `compose.desktop.application { mainClass = ... }` provides the `run` task the editor
// is launched with (CLAUDE.md build section) — needed from M2, when there is finally a window to run.
//
// Native packaging (nativeDistributions { ... }, packageDistributionForCurrentOS) requires the
// JDK 21 toolchain for jpackage (TECHNICAL_NOTES §12). It lives in `:app`'s own build script, not
// here: it is metadata for one concrete artifact (formats, version, icons, installer identity),
// verified against a real build (M10, ADR-022), so it does not belong in a shared convention.

import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.desktop.DesktopExtension

plugins {
    id("viewforge.compose-library")
}

extensions.configure<ComposeExtension> {
    extensions.configure<DesktopExtension> {
        application {
            mainClass = "viewforge.app.MainKt"
        }
    }
}

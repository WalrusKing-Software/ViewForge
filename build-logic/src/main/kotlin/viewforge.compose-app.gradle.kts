// Convention for the desktop entry point (:app). A Compose-aware module with a runnable
// application. `compose.desktop.application { mainClass = ... }` provides the `run` task the editor
// is launched with (CLAUDE.md build section) — needed from M2, when there is finally a window to run.
//
// Native packaging (nativeDistributions { ... }, packageDistributionForCurrentOS) is milestone M10
// and requires the JDK 21 toolchain for jpackage (PROJECT_PLAN §8 M10; TECHNICAL_NOTES §12). It is
// intentionally NOT configured here yet — wiring it untested would violate "don't claim something
// works without verifying" (CLAUDE.md). Add it in the M10 change, in :app, against a real build.

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

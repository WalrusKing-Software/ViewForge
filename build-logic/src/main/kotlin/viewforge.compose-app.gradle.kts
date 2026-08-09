// Convention for the desktop entry point (:app). For M0 this is just a Compose-aware module
// with an application main(). Native packaging (compose.desktop.application { nativeDistributions
// { ... } }, packageDistributionForCurrentOS) is milestone M10 and requires the JDK 21 toolchain
// for jpackage (PROJECT_PLAN §3.1 / §8 M10; TECHNICAL_NOTES §12). It is intentionally NOT wired
// here yet — configuring it untested would violate "don't claim something works without verifying"
// (CLAUDE.md). Add it in the M10 change, in :app, against a real build.

plugins {
    id("viewforge.compose-library")
}

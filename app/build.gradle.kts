import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("viewforge.compose-app")
}

// app: desktop entry point and dependency wiring (ARCHITECTURE §3). This is the one place
// allowed a compile-time dependency on packages/compose, to register the framework package with
// the editor (ARCHITECTURE §3 editor/* exception). Keep that wiring to a single clearly-marked
// file.
dependencies {
    implementation(projects.editor.shell)
    implementation(projects.editor.state)
    // Startup load of the editor-preferences file (ADR-023): apply the persisted panel layout before
    // the first frame so there is no layout flash. The shell owns the save side.
    implementation(projects.core.prefs)

    // Bootstrapping wiring: statically link the Compose package (Phase 1, ADR-007 §6.3). A
    // compile-time dependency (not runtimeOnly) because Main.kt binds CanvasRenderer to
    // ComposeRenderer directly — the one file allowed to name the package (ARCHITECTURE §3).
    implementation(projects.packages.compose)

    // currentOs (not the bare desktop coordinate) so the OS-specific skiko native library is on the
    // runtime classpath — without it `run` fails at startup with a skiko LibraryLoadException. This
    // is the one place the compose DSL is used directly, as the catalog can't express currentOs.
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)

    // The M9 fidelity test renders a hand-written Material3 composable twin beside the interpreter and
    // pixel-compares them (exit criterion #3); it needs material3/foundation directly on the test
    // classpath (they reach the app only transitively, as `implementation` of packages/compose).
    testImplementation(libs.compose.material3)
    testImplementation(libs.compose.foundation)
}

// Expose the repo-root samples/ directory so the sample-coherence test can assert the in-code
// sampleProject() stays byte-identical to the committed samples/Gallery.vforge (M9).
tasks.withType<Test>().configureEach {
    systemProperty("viewforge.samplesDir", rootProject.file("samples").absolutePath)
}

// --- M10: native packaging (PROJECT_PLAN §8; SECURITY §9 DI-1…DI-5; ADR-022) ------------------
// jpackage-based installers via Compose Desktop's nativeDistributions — vanilla, fully offline, no
// paid tooling and no auto-update (ADR-022, keeping ADR-011's no-network posture). `mainClass` is
// already set by the viewforge.compose-app convention plugin; this only adds the distribution
// metadata, so the two `application { }` blocks merge. Deliberately kept here, in :app, against a
// real build (the convention plugin's M10 note).
//
// Phase-1 target formats are Windows (Msi/Exe) and Linux (Deb/Rpm) — the M10 "at least Win + Linux"
// bar. macOS (Dmg) is deliberately NOT declared: jpackage rejects a version whose major is 0, so a
// pre-1.0 mac package can't be built without misreporting the version, and it can't be verified
// without a Mac runner + a branded .icns anyway. It is a documented follow-up (INSTALL.md), not a
// Phase-1 gate; adding it is a localised change (the Dmg format + macOS block + a mac version).
//
// Signing is NOT done by Gradle here: the Compose plugin only wires macOS signing/notarization.
// Windows Authenticode and Linux repo/GPG signing are per-OS post-build steps in the release
// workflow (.github/workflows/release.yml), gated on CI secrets. A build on a developer machine is
// therefore unsigned by design (DI-3: signed artifacts come from the tagged CI build, not a laptop).
compose.desktop {
    application {
        nativeDistributions {
            targetFormats(
                TargetFormat.Msi,
                TargetFormat.Exe,
                TargetFormat.Deb,
                TargetFormat.Rpm,
            )

            packageName = "ViewForge"
            // Single source of truth (gradle.properties), read through `providers` so it is a
            // configuration-cache-tracked input rather than a raw `project.property` access.
            packageVersion = providers.gradleProperty("viewforge.version").get()
            description = "A WYSIWYG editor that builds Compose Multiplatform UIs and generates idiomatic Kotlin."
            vendor = "WalrusKing Software"
            copyright = "© 2026 WalrusKing Software. All rights reserved."

            // Bundle a full runtime image rather than trimming with jlink module detection. jpackage
            // strips the JDK to the modules listed here; a missing module surfaces only when the
            // *installed* app runs a code path that needs it — which building the installer does not
            // exercise, so no packaging test can catch it. Shipping every module trades a larger
            // installer for a guarantee the packaged app has the same classpath the verified `run`
            // build has. Trimming via `suggestRuntimeModules` is a post-M10 size optimisation (INSTALL.md).
            includeAllModules = true

            windows {
                iconFile.set(project.file("src/main/resources/packaging/icon.ico"))
                menuGroup = "ViewForge"
                // Start-menu + desktop shortcuts.
                menu = true
                shortcut = true
                // Stable across versions so an MSI upgrade replaces the prior install instead of
                // installing side-by-side. Never regenerate this once released (ADR-022).
                upgradeUuid = "10312d4d-251f-4fc7-a134-2bf7c1f5097a"
            }

            linux {
                iconFile.set(project.file("src/main/resources/packaging/icon.png"))
                // Debian/RPM package name base (lowercase, no spaces).
                packageName = "viewforge"
                menuGroup = "Development"
            }
        }
    }
}

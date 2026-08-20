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
// Target formats cover all three desktop OSes: Windows (Msi/Exe), Linux (Deb/Rpm), macOS (Dmg). The
// .dmg is built only on the macos-latest GitHub runner (release.yml / matrix.yml) — a homelab Forgejo
// has no Mac runner, so mac packaging lives entirely in .github (#291). For the alpha the .dmg is
// unsigned and un-notarized (Gatekeeper warns; INSTALL.md documents the one-time bypass), matching the
// unsigned-Windows posture (DI-3). A branded macOS .icns is a follow-up (INSTALL.md §5); until then
// jpackage falls back to its default app icon. The numeric packageVersion (gradle.properties) stays the
// single source of truth across every OS.
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
                TargetFormat.Dmg,
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

            macOS {
                // Reverse-DNS bundle identifier. Stable across versions (like the Windows upgradeUuid), so
                // never regenerate it once released (ADR-022). No custom iconFile yet — a branded .icns is a
                // follow-up (INSTALL.md §5), so jpackage uses its default icon. Unsigned/un-notarized for the
                // alpha; Apple signing + notarization are gated CI steps for a real release (SECURITY DI-1/DI-3).
                bundleID = "software.walrusking.viewforge"

                // jpackage rejects a macOS version whose major is 0, so the .dmg carries a mac-ONLY artifact
                // version with the major forced to 1 (app 0.x.y -> dmg 1.x.y, e.g. 0.2.0 -> 1.2.0). This is NOT
                // the application version — that stays 0.x in gradle.properties and in the Windows/Linux
                // packages; this exists solely to satisfy jpackage's mac rule (#291, INSTALL.md §5). Revisit at
                // the 1.0 release, where the real version is already valid and this mapping becomes the identity.
                dmgPackageVersion = providers.gradleProperty("viewforge.version").get().let { v ->
                    v.split(".").let { p -> if (p.size == 3 && p[0] == "0") "1.${p[1]}.${p[2]}" else v }
                }
            }
        }
    }
}

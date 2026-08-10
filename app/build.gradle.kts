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

    // Bootstrapping wiring: statically link the Compose package (Phase 1, ADR-007 §6.3). A
    // compile-time dependency (not runtimeOnly) because Main.kt binds CanvasRenderer to
    // ComposeRenderer directly — the one file allowed to name the package (ARCHITECTURE §3).
    implementation(projects.packages.compose)

    // currentOs (not the bare desktop coordinate) so the OS-specific skiko native library is on the
    // runtime classpath — without it `run` fails at startup with a skiko LibraryLoadException. This
    // is the one place the compose DSL is used directly, as the catalog can't express currentOs.
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

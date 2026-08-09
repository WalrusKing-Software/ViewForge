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

    // Bootstrapping wiring: statically link the Compose package (Phase 1, ADR-007 §6.3).
    runtimeOnly(projects.packages.compose)

    implementation(libs.compose.desktop)
    implementation(libs.kotlinx.coroutines.swing)
}

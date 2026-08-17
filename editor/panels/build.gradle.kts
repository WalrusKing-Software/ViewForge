plugins {
    id("viewforge.compose-library")
}

// editor/panels: palette, tree panel, and the data-driven inspector (ARCHITECTURE §2).
// The inspector is generated from PropDefinition — no per-component UI (CLAUDE.md anti-patterns).
dependencies {
    api(projects.editor.state)
    implementation(projects.core.spi)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    // currentOs puts the OS-specific skiko native library on the *test* classpath so
    // ThemeEditorLayoutTest can rasterize the dialog body via ImageComposeScene (#162); without it the
    // render fails with a skiko LibraryLoadException. Test-only, mirroring :app's fidelity-test setup.
    testImplementation(compose.desktop.currentOs)
}

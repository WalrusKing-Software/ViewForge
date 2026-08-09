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
}

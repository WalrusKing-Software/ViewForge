plugins {
    id("viewforge.compose-library")
}

// editor/canvas: live IR rendering, hit-testing, selection/drop overlays (ARCHITECTURE §4).
// Talks to framework packages through core/spi, never packages/compose directly.
dependencies {
    api(projects.editor.state)
    implementation(projects.core.spi)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
}

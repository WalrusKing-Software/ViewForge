plugins {
    id("viewforge.compose-library")
}

// editor/state: EditorState, selection, document session, history wiring (ARCHITECTURE §5).
// Exposes IR/session state as Compose state, so it needs the Compose runtime but not the UI kit.
dependencies {
    api(projects.core.model)
    api(projects.core.command)
    api(projects.core.project)
    api(projects.core.prefs)
    api(projects.core.spi)

    implementation(libs.compose.runtime)
    implementation(libs.kotlinx.coroutines.core)
}

plugins {
    id("viewforge.compose-library")
}

// editor/shell: window, menus, dialogs, theming — assembles the panels and canvas
// (ARCHITECTURE §2). The top of the editor UI; wired into an application by :app.
dependencies {
    api(projects.editor.state)
    implementation(projects.editor.canvas)
    implementation(projects.editor.panels)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}

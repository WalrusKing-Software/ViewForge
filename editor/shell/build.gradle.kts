plugins {
    id("viewforge.compose-library")
}

// editor/shell: window, menus, dialogs, theming — assembles the panels and canvas
// (ARCHITECTURE §2). The top of the editor UI; wired into an application by :app.
dependencies {
    api(projects.editor.state)
    // api, not implementation: EditorShell's signature exposes CanvasRenderer (defined in canvas),
    // so :app must see the type to construct one (ARCHITECTURE §3 wiring exception).
    api(projects.editor.canvas)
    implementation(projects.editor.panels)
    // Editor-preferences persistence (ADR-023) — the shell saves panel layout directly, no framework
    // coupling, exactly as DocumentControls calls ProjectStore (the #37 no-seam precedent).
    implementation(projects.core.prefs)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    // currentOs (not the bare desktop coordinate) puts the OS-specific skiko native library on the
    // *test* classpath so ToolbarLayoutTest can rasterize the toolbar via ImageComposeScene (#161);
    // without it the render fails with a skiko LibraryLoadException. Test-only — the shell ships no
    // desktop entry point of its own (that is :app's role). The compose DSL is used directly here as
    // the version catalog can't express currentOs, mirroring :app's fidelity-test setup.
    testImplementation(compose.desktop.currentOs)
}

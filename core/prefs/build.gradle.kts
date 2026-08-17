plugins {
    id("viewforge.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

// core/prefs: the editor-preferences store (ADR-023). Per-user editor chrome — panel layout today,
// window size / recent files / S5 prefs later — persisted to a config file, kept strictly separate
// from the .vforge document. Compose-free. Depends on core/project only to reuse the guarded writer
// (CLAUDE.md rule 6), so there is one path-safety implementation, not two.
dependencies {
    api(projects.core.project)
    implementation(libs.kotlinx.serialization.json)
}

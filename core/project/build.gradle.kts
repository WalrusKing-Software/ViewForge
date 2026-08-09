plugins {
    id("viewforge.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

// core/project: .vforge (de)serialization, schema versioning, migrations, the guarded writer
// (CLAUDE.md rule 6). Depends on the IR only — no framework.
dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.serialization.json)
}

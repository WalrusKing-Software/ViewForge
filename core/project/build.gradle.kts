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

// Expose the repo-root samples/ directory to tests via an absolute path, so the fixture load test
// works regardless of the test's working directory.
tasks.withType<Test>().configureEach {
    systemProperty("viewforge.samplesDir", rootProject.file("samples").absolutePath)
}

plugins {
    id("viewforge.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

// core/model is the IR: pure Kotlin data classes. ARCHITECTURE §3 / CLAUDE.md rule 1:
// ZERO dependencies beyond stdlib and kotlinx.serialization. No Compose, no UI, no I/O.
dependencies {
    api(libs.kotlinx.serialization.json)
}

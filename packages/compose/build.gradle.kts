plugins {
    id("viewforge.compose-library")
}

// packages/compose: THE Compose framework package (ADR-008) — component & modifier definitions,
// runtime renderers, KotlinPoet emitters, and per-target exporters. The one and only SPI
// implementation until Phase 5 (ADR-007). This is the module the core boundary protects.
dependencies {
    api(projects.core.spi)
    implementation(projects.core.model)

    // Codegen: KotlinPoet structural API only — never string concatenation (CLAUDE.md rule 4).
    implementation(libs.kotlinpoet)

    // Runtime renderers use real Compose composables (ARCHITECTURE §4.1).
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}

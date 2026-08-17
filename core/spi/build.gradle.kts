plugins {
    id("viewforge.kotlin-library")
}

// core/spi: the interfaces a framework package implements (ARCHITECTURE §6). Nothing here
// mentions Compose (ADR-007). Pure data schema half only; the renderer half lives in
// packages/compose. Depends on the IR.
dependencies {
    api(projects.core.model)
}

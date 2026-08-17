plugins {
    id("viewforge.kotlin-library")
}

// core/command: command pattern, undo/redo stacks, transaction grouping (ARCHITECTURE §5).
// All document mutations go through here (CLAUDE.md rule 3). No framework dependency.
dependencies {
    api(projects.core.model)
}

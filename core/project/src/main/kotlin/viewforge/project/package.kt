package viewforge.project

/**
 * core/project: `.vforge` (de)serialization, schema versioning, migration chain, and the single
 * guarded writer for all file writes (CLAUDE.md rule 6, ARCHITECTURE §3). Implementation lands
 * at M1; this marker keeps the module compiling and linted from M0.
 */
internal const val MODULE = "core:project"

package viewforge.model

/**
 * The `.vforge` schema version. Every project file carries this and every load path runs
 * migrations against it (PROJECT_PLAN §5, DATA_MODEL). Bumping it is a breaking change that
 * requires a migration and a fixture (CLAUDE.md "When uncertain").
 *
 * The IR data classes themselves land in M1; this constant exists so the module is non-empty
 * and the build/lint/test toolchain is exercised from M0.
 */
const val SCHEMA_VERSION: Int = 1

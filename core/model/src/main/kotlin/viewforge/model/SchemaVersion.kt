package viewforge.model

/**
 * The `.vforge` schema version. Every project file carries this and every load path runs
 * migrations against it (PROJECT_PLAN §5, DATA_MODEL). Bumping it is a breaking change that
 * requires a migration and a fixture (CLAUDE.md "When uncertain").
 *
 * v2 (ADR-028) adds [PropValue.ParamRef] for component parameters. The change is data-additive —
 * a v1 document is already a valid v2 document — but a new member of the *closed* [PropValue]
 * hierarchy cannot be deserialized by a v1-only build, so it is forward-incompatible and gets a
 * version bump per DATA_MODEL §10. The 1->2 migration only stamps the version (M1to2).
 */
const val SCHEMA_VERSION: Int = 2

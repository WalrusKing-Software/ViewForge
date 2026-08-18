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
 *
 * v3 (ADR-034, #21) adds read-only screen state: [Screen.state] ([StateField]s) and the
 * `vforge.repeat` node ([Repeater]), bound to via [PropValue.StateBinding]. Populating any of it is
 * likewise forward-incompatible — a v2-only build would silently drop `state` (an unknown key) and
 * misrender every binding — so it too gets a bump. A v2 document carries no state and is already a
 * valid v3 document, so the 2->3 migration only stamps the version (M2to3).
 */
const val SCHEMA_VERSION: Int = 3

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
 *
 * v4 (ADR-034 Amendment, #255) makes screen state **recursive** — a [RecordField] holds a full
 * [StateType], so a record field may itself be a list-of-record (nested lists), and a sample cell is a
 * [SampleValue] (scalar or nested rows). This changes the *serialized shape* of existing v3 record fields
 * (`{name, scalar}` → `{name, type}`) and sample cells (a bare primitive → `{kind:"scalar", value}`), so
 * unlike M1to2/M2to3 the 3->4 migration (M3to4) actually **transforms** the document, not just stamps it.
 * (ADR-030 responsive, previously reserved for v4, slides to v5.)
 *
 * v5 (ADR-034 Amendment, component-local state) adds [ComponentDef.state] — a component gets its own
 * read-only screen-style [StateField]s, resolved against itself (never the enclosing screen). Additive: a
 * v4 document has no component state and is already a valid v5 document, so the 4->5 migration only stamps
 * the version (M4to5). The bump exists for the same reason as M2to3 — a v4-only build would silently drop
 * component `state` and misrender every component-local binding — so a v5 file must be refused cleanly by
 * older builds (ProjectStore NEWER_SCHEMA gate). (ADR-030 responsive consequently slides to v6/M5to6.)
 *
 * v6 (ADR-035, #277) adds **interactive state & events**: a [Node] gains [Node.handlers] — event slots (e.g.
 * "onClick") each holding a `List<Action>` from the closed [Action] set — and screen/component [StateField]s
 * become writable *targets* of those actions. No evaluator is introduced (an [Action] is a structured operation
 * dispatched by `when`, never parsed — PF-4). Populating handlers is forward-incompatible — a v5-only build
 * would silently drop `handlers` and render a dead UI — so it claims v6 with an `M5to6` **stamp** migration (a
 * v5 document carries no handlers and is already a valid v6 document, like M2to3/M4to5). This takes the slot
 * ADR-030 responsive had reserved, so responsive slides to v7/M6to7.
 *
 * v7 (ADR-030, #221) adds **responsive per-breakpoint overrides**: a [Node] gains [Node.responsive] — a
 * breakpoint-id → (prop-name → override [PropValue]) map layered over the base [Node.props] at render/codegen
 * ([effectiveProps]). Additive: a v6 document has no `responsive` and is already a valid v7 document, so the
 * 6->7 migration only stamps the version (M6to7, like M1to2/M2to3/M4to5/M5to6). The bump exists because
 * populating overrides is forward-incompatible — a v6-only build would silently drop `responsive` and
 * render/emit only base props (a fidelity loss) — so a v7 file must be refused cleanly by older builds
 * (ProjectStore NEWER_SCHEMA gate).
 */
const val SCHEMA_VERSION: Int = 7

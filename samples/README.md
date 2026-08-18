# samples

Example `.vforge` projects used as test fixtures and for manual dogfooding (PROJECT_PLAN §4).
Not a Gradle module. Real fixtures are added alongside the IR and codegen work (M1/M6).

- `Demo.vforge` — the minimal M1 fixture (a themed title + button); round-trip test fixture. Kept
  pinned at `schemaVersion: 1` on purpose so it doubles as the 1->2 migration fixture (ADR-028): a
  load exercises `M1to2` end to end and the result is expected to report the current schema version.
- `Gallery.vforge` — the Phase-1 "something real" screen (M9): nested `Column`/`Row`/`Box`, `Text`,
  `Button`s, `Image`s, and a scrollable `LazyColumn`. The editor opens it on launch; it is the
  byte-identical serialization of `app`'s in-code `sampleProject()` (a test keeps the two in lockstep).
  Its image assets live on the app classpath under `app/src/main/resources/assets/`. Kept at the
  current schema version, so it doubles as the "already-current, nothing migrated" load fixture.
- `Dashboard.vforge` — the schema-v3 read-only-data-binding fixture (ADR-034, #21): a screen with
  `state` (a scalar `String`, a scalar `Bool`, and a list-of-record `members`), a `Text` bound to
  `title` via a `StateBinding`, and a `vforge.repeat` node whose per-item template binds `item.name`.
  Kept byte-identical to `Fixtures.stateProject()` (a test keeps the two in lockstep) and used for the
  v3 codec round-trip test.

# Compatibility

ViewForge carries **two independent version numbers**, and this page explains how they relate:

- The **application version** (e.g. `v0.2.0-alpha-1`) — the released build of the editor, tagged in
  Git and following [Semantic Versioning](https://semver.org/).
- The **`.vforge` schema version** (`schemaVersion`, an integer) — the on-disk format of a project
  file, versioned and migrated independently of the app. Every `.vforge` file records its own
  `schemaVersion`.

They move on separate clocks: an app release only bumps the schema when a feature changes the file
format. The schema is the durable contract — the authoritative definition lives in
[`_docs/DATA_MODEL.md` §10](_docs/DATA_MODEL.md); this page is the version-to-version compatibility
summary.

---

## App version ↔ schema version

| App version | Released | Reads `.vforge` up to | Writes | Notes |
|---|---|---|---|---|
| `v0.1.0-alpha-1` | 2026-08-17 | **v2** | **v2** | First public alpha (Phase 1, Compose Desktop). |
| `v0.2.0-alpha-1` | 2026-08-20 | **v6** | **v6** | Read-only data binding + interactive state & events (ADR-034, ADR-035). |

"Reads up to" is the highest `schemaVersion` a build understands. A build opens **any file at or
below** that version (older files are migrated forward on load — see below) and **refuses** a file
above it.

---

## `.vforge` schema history

Each schema version and the change that forced the bump. A **stamp** migration only re-labels the
version (the older document is already structurally valid at the new version); a **transform**
migration rewrites the document's shape.

| Schema | Introduced in app | Change | ADR | Migration |
|---|---|---|---|---|
| **v1** | ≤ `v0.1.0-alpha-1` | Initial `.vforge` format. | — | — |
| **v2** | `v0.1.0-alpha-1` | `PropValue.ParamRef` — component parameters; a new member of the *closed* `PropValue` union. | ADR-028 | `M1to2` (stamp) |
| **v3** | `v0.2.0-alpha-1` | `Screen.state` read-only data binding + the `vforge.repeat` node, bound via `PropValue.StateBinding`. | ADR-034 | `M2to3` (stamp) |
| **v4** | `v0.2.0-alpha-1` | Nested lists — a `RecordField` carries a full `StateType`; a sample cell is a `SampleValue`. **Changes the serialized shape** of existing state. | ADR-034 (amend. #255) | `M3to4` (**transform**) |
| **v5** | `v0.2.0-alpha-1` | Component-local state — `ComponentDef` gains its own `state`, resolved against itself. | ADR-034 (amend. #266) | `M4to5` (stamp) |
| **v6** | `v0.2.0-alpha-1` | Interactive state & events — state becomes **writable**; `Node.handlers` maps an event slot to an ordered, closed `List<Action>`. No evaluator is introduced (PF-4). | ADR-035 | `M5to6` (stamp) |
| **v7** *(reserved)* | Phase 2 | Node `responsive` per-breakpoint overrides. | ADR-030 | `M6to7` |

---

## Compatibility policy

**Opening an older file (backward compatibility).** A newer app opens any older `.vforge` file and
migrates it forward on load, chaining migrations (`v1→v2→…→v6`) without skipping. Migrations run on
the raw JSON, not deserialized classes, so no historical data class has to be kept alive. Before a
migrated project is written back, the original file is backed up first — a load-and-save never
silently overwrites the on-disk original with a migrated one.

**Opening a newer file (forward compatibility).** An older app **cannot** open a file whose
`schemaVersion` exceeds what it supports. It **fails loudly** with a clear message
(`file is schema vN; this build supports up to vM`) and performs **no partial load** — it never
guesses, drops unknown data, or renders a half-understood document. To open such a file, upgrade the
app.

**Why additive-looking changes still bump the version.** Several schema bumps (v3, v5, v6) only *add*
fields. They still require a version bump because the `.vforge` parser tolerates unknown keys: an
older build would *silently drop* the new data (state, component state, handlers) and misrender or
render a dead UI. Bumping the version lets older builds refuse the file cleanly instead. Only v4 is a
shape-changing transform. (Purely cosmetic optional fields that a default fully covers do not bump
the version — see the additive-change policy in `_docs/DATA_MODEL.md` §10.)

**Practical guidance.**

- Commit `.vforge` files to Git — they are pretty-printed with stable key order for clean diffs.
- If teammates share project files, keep everyone on the **same app version** (or newer). A newer
  app reads an older teammate's files; an older app cannot read a newer teammate's files.
- After a migrating open, saving upgrades the file to the current schema. Do this deliberately if
  others on an older app still need to open it.

---

## See also

- [`_docs/DATA_MODEL.md` §10](_docs/DATA_MODEL.md) — authoritative schema definition, migration rules,
  and version-history table.
- [`_docs/SECURITY.md`](_docs/SECURITY.md) — how untrusted `.vforge` files are validated on load
  (size/node/depth limits, cycle and path checks, fail-loud on newer/malformed schema).
- [`CHANGELOG.md`](CHANGELOG.md) — per-release notes.

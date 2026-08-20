# ViewForge

A desktop WYSIWYG UI editor for **Compose Multiplatform**, built to be extended with pluggable
language/framework packages over time.

ViewForge lets you compose a UI on a live canvas rendered by the *real* Compose runtime — not a
simulation — and export clean, idiomatic Kotlin source you own and can hand-edit.

---

## Status

**v0.2.0-alpha-1 — second alpha (Phase 1, Compose Desktop).** The editor builds, edits, themes, and
exports a non-trivial Compose Desktop screen, now with read-only **data binding** and interactive
**state & events**, a cross-project component library, and re-opening of ViewForge-generated Kotlin. It
ships **unsigned** installers for Windows (`.msi`/`.exe`), Linux (`.deb`/`.rpm`), and macOS (`.dmg`) — a
downloaded build may show a SmartScreen/Gatekeeper prompt. Early software: expect rough edges, and the
`.vforge` schema (v6) may still evolve behind migrations. Phase 2 (Android) is planned — see
[`_docs/PROJECT_PLAN.md`](_docs/PROJECT_PLAN.md).

## Why this exists

Existing Compose visual builders are mobile-first, cloud-coupled, or generate code you can't
comfortably maintain. ViewForge is desktop-first, local-first, and treats generated Kotlin as a
first-class artifact rather than a black box.

The longer-term goal is a **framework-agnostic editor shell** with swappable framework packages
(Compose first, others later). That generalization is deliberately deferred — see
[`_docs/ARCHITECTURE.md`](_docs/ARCHITECTURE.md) for why, and what is being done now to keep the door
open without over-designing for it.

## Roadmap at a glance

| Phase | Target | Status |
|-------|--------|--------|
| 1 | Compose Desktop (JVM) | **Feature-complete (latest: v0.2.0-alpha-1)** |
| 2 | + Android | Planned (next) |
| 3 | + iOS | Blocked on macOS hardware |
| 4 | + Web (Kotlin/Wasm) | Planned, experimental |
| 5 | Framework package SDK (non-Compose frameworks) | Future |

Full detail: [`_docs/PROJECT_PLAN.md`](_docs/PROJECT_PLAN.md)

## Documentation

| Document | Purpose |
|----------|---------|
| [`_docs/PROJECT_PLAN.md`](_docs/PROJECT_PLAN.md) | Scope, phases, tech stack, milestones, risks |
| [`_docs/ARCHITECTURE.md`](_docs/ARCHITECTURE.md) | Module layout, rendering model, plugin system |
| [`_docs/DATA_MODEL.md`](_docs/DATA_MODEL.md) | The IR and `.vforge` project file schema |
| [`_docs/FEATURES.md`](_docs/FEATURES.md) | Feature backlog, grouped and prioritized |
| [`_docs/BRANCHING.md`](_docs/BRANCHING.md) | Git workflow, commit conventions, releases |
| [`_docs/SECURITY.md`](_docs/SECURITY.md) | Threat model and security requirements |
| [`_docs/TECHNICAL_NOTES.md`](_docs/TECHNICAL_NOTES.md) | Known hard problems and decided approaches |
| [`_docs/DECISIONS.md`](_docs/DECISIONS.md) | Architecture Decision Record log |
| [`_docs/INSTALL.md`](_docs/INSTALL.md) | Installing the packaged app, per OS |
| [`_docs/RELEASE_QA.md`](_docs/RELEASE_QA.md) | Manual acceptance-test checklist gating each release |
| [`COMPATIBILITY.md`](COMPATIBILITY.md) | Which `.vforge` schema versions each app version reads/writes |
| [`CHANGELOG.md`](CHANGELOG.md) | Notable changes per release (Keep a Changelog) |

## Building

Requires **JDK 21** (Compose Desktop needs 11+; native packaging needs 17+ — the toolchain is pinned
to 21 in `gradle/libs.versions.toml`; see `_docs/PROJECT_PLAN.md`).

```bash
./gradlew :app:run          # run the editor
./gradlew allTests          # run tests
./gradlew :app:packageDistributionForCurrentOS   # produce a native installer
```

Installing a released build: [`_docs/INSTALL.md`](_docs/INSTALL.md).

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for how to build, test, and submit changes, and the branching
and commit conventions.

## License

[Apache License 2.0](LICENSE). © 2026 WalrusKing Software.

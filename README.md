# ViewForge

A desktop WYSIWYG UI editor for **Compose Multiplatform**, built to be extended with pluggable
language/framework packages over time.

ViewForge lets you compose a UI on a live canvas rendered by the *real* Compose runtime — not a
simulation — and export clean, idiomatic Kotlin source you own and can hand-edit.

---

## Status

**Pre-alpha. Phase 1 (Desktop target) in progress.** Nothing here is stable yet.

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
| 1 | Compose Desktop (JVM) | In progress |
| 2 | + Android | Planned |
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
| [`CLAUDE.md`](CLAUDE.md) | Working agreement for Claude Code |

## Building

Requires **JDK 17+** (see `_docs/PROJECT_PLAN.md` for exact toolchain constraints).

```bash
./gradlew :app:run          # run the editor
./gradlew allTests          # run tests
./gradlew :app:packageDistributionForCurrentOS   # produce a native installer
```

## License

TBD before first public release. See [`_docs/PROJECT_PLAN.md`](_docs/PROJECT_PLAN.md) § Open Questions.

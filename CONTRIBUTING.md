# Contributing to ViewForge

Thanks for your interest in ViewForge — a desktop WYSIWYG editor for **Compose Multiplatform** that
renders a UI tree with the real Compose runtime and generates idiomatic Kotlin/Compose source.

This guide covers how to build, test, and submit changes. For *why* the project is shaped the way it
is, read the design docs first (see [Before you start](#before-you-start)).

---

## Before you start

The [`_docs/`](_docs/) directory is the source of truth for design; read the doc relevant to your
change before writing code:

| Working on | Read first |
|---|---|
| Anything structural | [`_docs/ARCHITECTURE.md`](_docs/ARCHITECTURE.md) |
| IR, the `.vforge` format, props, modifiers | [`_docs/DATA_MODEL.md`](_docs/DATA_MODEL.md) |
| A specific feature | [`_docs/FEATURES.md`](_docs/FEATURES.md) (has acceptance criteria) |
| Codegen, canvas, hit-testing, performance | [`_docs/TECHNICAL_NOTES.md`](_docs/TECHNICAL_NOTES.md) |
| File I/O, parsing untrusted input | [`_docs/SECURITY.md`](_docs/SECURITY.md) |
| "Why is it done this way?" | [`_docs/DECISIONS.md`](_docs/DECISIONS.md) |
| Git workflow, commits, releases | [`_docs/BRANCHING.md`](_docs/BRANCHING.md) |

[`_docs/DECISIONS.md`](_docs/DECISIONS.md) records **rejected** alternatives with reasons — check it
before proposing an approach so a settled decision isn't re-litigated. If the docs don't cover
something, say so in the issue or PR rather than inventing a convention silently.

**Propose a plan first** for anything beyond a small fix. Correcting a plan is far cheaper than
correcting a large diff.

---

## Toolchain & build

ViewForge targets **JDK 21** (Compose Desktop needs 11+; native packaging needs 17+). The toolchain
is pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) and applied via the
`viewforge.kotlin-library` convention plugin, so you don't need to match it exactly to build — but 21
is what CI uses.

```bash
./gradlew :app:run                                # run the editor
./gradlew allTests                                # run all tests
./gradlew :packages:compose:test                  # codegen golden tests
./gradlew spotlessApply                           # format (run before pushing)
./gradlew :app:packageDistributionForCurrentOS    # produce a native installer
```

All dependency versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). **Do
not introduce dynamic versions.**

---

## Project rules

These invariants are enforced by tests and review. A change that breaks one of them is wrong even if
it compiles:

1. **`core/` must not depend on Compose.** Framework specifics live in `packages/compose`; there is an
   architecture test enforcing this. If it fails, fix the design, not the test.
2. **Modifiers are an ordered list**, never a map — order is semantic in Compose. Don't reorder them
   anywhere in the pipeline ([`_docs/DATA_MODEL.md`](_docs/DATA_MODEL.md)).
3. **All document mutations go through commands** in `core/command`. No direct mutation — undo/redo
   depends on it.
4. **Codegen uses KotlinPoet's structural API.** Never build Kotlin source with string concatenation
   or interpolation — this is a security requirement ([`_docs/SECURITY.md`](_docs/SECURITY.md) GC-1/GC-2),
   not a style choice.
5. **Every new component or modifier needs a golden-file test** — an IR fixture in, expected `.kt`
   out — and CI **compiles** the generated fixtures, so string-equality alone is not enough.
6. **All file writes go through the guarded writer** in `core/project`. No scattered `File.writeText`;
   path validation lives in one place.
7. **No network calls.** ViewForge is entirely offline ([ADR-011](_docs/DECISIONS.md)); adding one is a
   security-relevant change requiring a documented decision.
8. **Never evaluate user-supplied expressions.** A `RawExpression` is stored, displayed, and emitted as
   text — never executed.
9. **The inspector is data-driven** from `PropDefinition`; adding a component must not require
   per-component inspector UI.

If a change would alter the `.vforge` schema, it needs a version bump, a migration, and a fixture —
flag it early ([`_docs/DATA_MODEL.md`](_docs/DATA_MODEL.md) §10).

---

## Branching & commits

ViewForge follows **Git Flow**. The full model is in [`_docs/BRANCHING.md`](_docs/BRANCHING.md); the
essentials:

- **Always branch from `develop`, never from `main`.** `main` holds only released code.
- Branch names are `<type>/<short-kebab-description>`, where `<type>` is one of
  `feature` · `bugfix` (or `fix`) · `chore` · `docs` · `refactor` · `test`. (`hotfix/*` is the one
  exception that branches from `main`, for bugs in an already-released version.)
- Keep branches short-lived and scoped to one concern. Rebase onto `develop` before opening the PR.

Commits follow [Conventional Commits](https://www.conventionalcommits.org/), scoped by module
(`core`, `model`, `canvas`, `inspector`, `codegen`, `compose-pkg`, `app`, `ci`, `docs`, …). Schema
changes are breaking:

```
feat(canvas): add drag-to-reparent with drop validation
fix(codegen): preserve modifier order when weight is present
feat(model)!: split children into children + slots

BREAKING CHANGE: schemaVersion → 2; migration in core/project/migrations/M1to2.kt
```

Write the commit body to explain *why*, not *what*. Never commit a diff you haven't read.

---

## Pull requests

Open every change as a PR into `develop` (features/fixes) — `main` is only for release/hotfix merges.

Expectations:

- **Small, reviewable diffs.** Split a sprawling change into logical commits before opening the PR.
- **Tests with the code, not after.** New behavior needs unit tests; new components/modifiers need a
  golden-file test; touched codegen must still compile its fixtures.
- **Update docs in the same PR** when behavior diverges from what a `_docs/` file states — stale docs
  actively mislead.
- **Run `./gradlew spotlessApply`** so formatting is clean, and make sure `./gradlew allTests` passes
  locally.

The PR checklist (base branch, tests, golden files, schema migration, manual verification) is in
[`_docs/BRANCHING.md`](_docs/BRANCHING.md) §9 — please self-check against it.

---

## Filing issues

The issue tracker is the source of truth for what needs doing. Before starting non-trivial work,
check for an existing issue (or open one) so the work is visible and duplication is avoided. A good
report says what you expected, what happened, and the minimal steps to reproduce; for a `.vforge`
problem, attach or paste the file if you can.

---

## License

By contributing, you agree that your contributions are licensed under the project's
[Apache License 2.0](LICENSE). © 2026 WalrusKing Software.

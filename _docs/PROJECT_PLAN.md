# ViewForge — Project Plan

**Status:** Phase 1 (Compose Desktop) **shipping as v0.1.0-alpha-1**; Phase 2 (Android) planned next.
**Last updated:** August 2026

---

## 1. Product definition

### 1.1 One-line

A local-first desktop WYSIWYG editor that produces idiomatic Compose Multiplatform source code,
architected so that support for additional languages and frameworks can be added later as packages.

### 1.2 What it is

- A **Compose Desktop application** (Kotlin/JVM) that runs on Windows, macOS, and Linux.
- An editor where you build a UI tree on a canvas rendered by the **real Compose runtime**, giving
  genuine WYSIWYG fidelity rather than an approximation.
- A **code generator** that turns the edited UI tree into readable, hand-editable Kotlin/Compose
  source files.
- A **project format** (`.vforge`) that is the editor's source of truth, versioned and diffable.

### 1.3 What it is not (v1)

Explicit non-goals, stated so they don't creep in:

- **Not a full IDE.** No Kotlin editing, no debugger, no refactoring tools.
- **Not a code round-trip tool.** v1 does not parse arbitrary hand-written Compose source back into
  the editor. This is the single largest scope risk in the project and is deliberately excluded.
  See §7.1. (Re-opening output ViewForge *itself* generated — carried as an IR sidecar and recognised
  by ownership, never parsed — is a narrow sanctioned exception; see ADR-032.)
- **Not a build system.** ViewForge generates source; the user compiles and runs it in their own
  IDE/Gradle setup. ViewForge does not bundle a JDK, Gradle, Android SDK, or Xcode toolchain.
- **Not a backend/app-logic builder.** No visual state machines, no database bindings, no API
  designer in v1. UI structure and styling only.
- **Not cloud-connected.** No accounts, no sync, no telemetry in v1.

### 1.4 Primary user

Initially: the author, building real Compose Multiplatform projects. Design for a developer who can
read Kotlin and will hand-edit generated output — **not** for a non-technical no-code user. This
assumption justifies prioritizing code quality of the output over hand-holding in the UI.

---

## 2. Phasing

Each phase must be **end-to-end complete and dogfooded** before the next begins. Do not start
Phase N+1 to escape a hard problem in Phase N.

### Phase 1 — Compose Desktop (JVM) — ✅ **feature-complete (v0.1.0-alpha-1)**

**Goal:** Build a non-trivial desktop screen in the editor, export it, compile and run it outside
the editor, and have it look the same.

Scope:
- Editor shell: canvas, component palette, layer/tree panel, property inspector.
- Core IR + `.vforge` file format with schema versioning.
- Live interpreted rendering of the IR using real Compose composables.
- Selection, drag-to-reparent, reorder, delete, undo/redo.
- A constrained component set and modifier set (see `_docs/FEATURES.md`).
- Kotlin/Compose codegen via KotlinPoet, emitting `commonMain`-compatible composables.
- Export as either loose `.kt` files or a full runnable Gradle project scaffold.

**Exit criteria (all met):**
1. ✅ A screen with nested `Column`/`Row`/`Box`, text, buttons, images, and a scrollable list can be
   built entirely in the editor (the `Gallery` sample).
2. ✅ Exported code compiles with zero manual fixes (CI compile gate, `CompilationTest`).
3. ✅ Rendered canvas and compiled output are visually identical (interpreter-vs-composable fidelity
   test, `FidelityTest`).
4. ✅ Save → close → reopen restores the project losslessly (`RoundTripTest`).
5. ✅ Undo/redo is correct across ≥50 mixed operations (property-based `UndoRedoPropertyTest`).
6. ✅ Golden-file codegen tests cover every supported component and modifier (`GoldenCodegenTest`).

**Shipped beyond the exit bar** (see `FEATURES.md` for IDs): multi-select + marquee, reusable
components with parameters, edit-in-place, device preview frames, live code preview with node↔code
mapping, command palette, preferences dialog, crash recovery + reporter, safe regeneration, disk image
import into the project (#141), and native installers (M10; unsigned for the alpha — see the release
milestone below). The acceptance run is gated by [`RELEASE_QA.md`](RELEASE_QA.md).

### Phase 2 — Android target — **planned (next)**

**Goal:** the *same* `.vforge` project that exports a Desktop app also exports a runnable Android app,
and the canvas can preview it in an Android device frame that matches the real device within tolerance.

**Scope:**
- **Source-set-aware export (G9).** The Compose package gains an Android `TargetDefinition` and the
  exporter routes generated files to `commonMain` vs `androidMain` (and emits an Android
  `build.gradle.kts` + manifest scaffold). Most UI is `commonMain`; only platform entry points and
  Android-only affordances land in `androidMain`. `sourceSetFor(file)` (SPI, ARCHITECTURE §6.2) becomes
  real for the first time.
- **Android device preview frames.** Extend the `DeviceProfiles` registry (ADR-026) with Android
  profiles carrying **density** and **safe-area/system-bar insets** (status bar, navigation bar,
  cutout). The framed canvas already clips/centres to a profile; Phase 2 adds density scaling and inset
  chrome so the preview reflects a real device, not just a desktop rectangle.
- **Responsive overrides (I9).** Per-breakpoint prop values, modelled per **ADR-030**: an additive
  node field `responsive` keyed by breakpoint id (Material window size classes for Android), resolved at
  render (canvas shows the active breakpoint) and codegen (emit the base value; a later slice may emit
  `BoxWithConstraints`/window-size-class branching). This is the one **schema-affecting** item —
  bump **6 → 7** with an `M6to7` migration and a committed fixture (DATA_MODEL §10). (v3 was claimed by
  ADR-034 read-only data binding, v4 by its nested-lists amendment (#255), v5 by its component-local-state
  amendment (#266), and v6 by ADR-035 interactive state & events, so responsive re-versioned from its original
  v3 scope to v7.)
- **Android-specific validation warnings.** Non-blocking inspector/validation hints, e.g. touch-target
  minimum sizes (48dp), and unset `contentDescription` on interactive/image nodes (accessibility). Fail
  loud in the inspector (I8), never at codegen.
- **Verification.** An Android compile gate in CI (the `androidMain` output compiles against the
  Android Compose artifacts), mirroring the Phase-1 desktop compile gate. Visual parity is checked
  against an emulator/device.

**Out of scope for Phase 2** (still deferred): iOS/Web targets (Phases 3/4), dynamic framework
packages (Phase 5), navigation-graph editing (ADR-035's `Navigate` is only a structural hook, #214), and
free-form expression evaluation. Data binding and closed-action interactivity already shipped (ADR-034 +
ADR-035, v0.2.0), independent of the target work.

**Exit criteria (all must pass):**
1. The same `.vforge` used for Desktop exports a Gradle project that builds and runs a runnable Android
   app (`./gradlew installDebug` or Android Studio) with **zero manual fixes**.
2. Generated files are routed correctly to `commonMain` vs `androidMain`, and the Android output
   compiles in CI.
3. The canvas Android device frame (density + insets) matches the app on a real device/emulator within
   tolerance (screenshot diff, mirroring Phase-1 exit #3).
4. A screen with at least one responsive override renders correctly per breakpoint on the canvas and
   round-trips losslessly through the schema-7 format (with a passing `M6to7` migration fixture).
5. Golden codegen tests cover the Android target output and the responsive-override emission.

**Prerequisite design decisions (record before coding):** ADR-030 (responsive data model — decided);
still to decide at Phase-2 kickoff — the responsive *codegen* strategy (window-size-class branching vs
`commonMain` base only), and the exact Android scaffold (min/target SDK, AGP/Compose-Android versions,
pinned in the catalog per DS-1).

### Phase 3 — iOS target *(blocked: requires macOS hardware)*

Scope:
- iOS target exporter and iOS device preview frames (safe areas, notch/dynamic island insets).
- Verification pipeline requires a Mac with Xcode. Codegen can be written and unit-tested without
  one; **visual verification cannot.**

**Do not claim iOS support until it has been verified on real hardware.** Untested codegen is a
liability, not a feature.

### Phase 4 — Web target (Kotlin/Wasm) *(experimental)*

Compose Multiplatform for web is the least mature of the four targets and its APIs and tooling are
still moving. Treat this phase as explicitly experimental and version-pinned; expect to revisit it.

### Phase 5 — Framework package SDK

Only after Compose support is genuinely good. Extract the framework-specific parts of the editor
behind a stable SPI so non-Compose packages become possible. See `_docs/ARCHITECTURE.md` §6.

---

## 3. Tech stack

> **Verify these versions before pinning them.** They reflect the ecosystem as of August 2026 and
> move fast. Use a Gradle version catalog (`gradle/libs.versions.toml`) so upgrades are one-file
> changes.

### 3.1 Core

| Concern | Choice | Rationale |
|---|---|---|
| Language | Kotlin (JVM) | Same language as the output; enables sharing the component model between editor and codegen. |
| UI framework | Compose Multiplatform (desktop/JVM) | The editor renders real Compose — dogfooding gives free WYSIWYG fidelity. |
| Build | Gradle (Kotlin DSL), multi-module | Standard for KMP; required for the output projects anyway. |
| JDK | **21** (LTS ≥ 17) | Compose Desktop requires JDK 11 minimum due to Skia binding memory management; **17+ is required to package native distributions.** Standardized on 21 (installed toolchain) to avoid a split; pinned in the version catalog. |
| Serialization | `kotlinx.serialization` (JSON) | Multiplatform-ready, schema-friendly, good default-value handling for forward compat. |
| Codegen | **KotlinPoet 2.x** (`com.squareup:kotlinpoet`) | The mature, actively maintained Kotlin source generator. Produces structurally valid code rather than string-concatenated guesses. |
| Concurrency | Kotlin Coroutines + `StateFlow` | Standard; integrates cleanly with Compose state. |
| Logging | SLF4J + Logback (or `kotlin-logging`) | Needed for diagnosing plugin/codegen failures. |

**Note on KotlinPoet 2.x:** the 2.0 release changed line-wrapping behavior — spaces no longer wrap
automatically, and a `♢` placeholder marks wrappable spaces. This directly affects generated-code
formatting. Budget time for formatting tuning and lock it down with golden-file tests.

### 3.2 Editor UI

| Concern | Choice | Notes |
|---|---|---|
| Widget toolkit | Compose Multiplatform | — |
| IDE-style chrome | **Jewel** (JetBrains) — *optional, evaluate first* | Gives an IntelliJ-native look, but **requires the JetBrains Runtime (JBR)** rather than a stock JDK. That's a real constraint on your build and distribution. Do not adopt it without deciding you accept the JBR dependency. Default to plain Material 3 if unsure. |
| Icons | Compose Material Icons + custom | — |
| Packaging | Compose Desktop `nativeDistributions` / jpackage (**chosen**, ADR-022). Conveyor evaluated and rejected. | Vanilla jpackage: offline, no paid product, no auto-update (ADR-011). Conveyor's cross-OS packaging + auto-update was rejected because auto-update is a network channel ADR-011 excludes. See `_docs/INSTALL.md`. |

### 3.3 Testing

| Layer | Approach |
|---|---|
| IR / model | Plain `kotlin.test` unit tests. Property-based tests for the command/undo stack are worth the setup. |
| Codegen | **Golden-file tests.** Every component and modifier has a fixture: IR in, expected `.kt` out. This is the single highest-value test suite in the project. |
| Codegen validity | Compile generated fixtures in CI as a real compilation check — not just string comparison. |
| Editor UI | Compose UI test APIs (note: the v2 test APIs are now the default; v1 is deprecated). |
| Visual fidelity | Screenshot/golden-image diff between the editor canvas and compiled output. |

### 3.4 CI

GitHub Actions:
- `build` — compile, unit tests, ktlint/spotless, on Linux.
- `codegen-verify` — generate fixtures and **compile them** to catch invalid output.
- `matrix` — build on Windows/macOS/Linux before releases.
- Dependency scanning (see `_docs/SECURITY.md`).

---

## 4. Repository layout

```
viewforge/
├── _docs/                       # all project documentation (underscore sorts it first)
├── build-logic/                 # convention plugins, shared Gradle config
├── gradle/libs.versions.toml    # single source of truth for versions
├── core/
│   ├── model/                   # IR data classes, no framework deps
│   ├── project/                 # .vforge (de)serialization, migrations
│   ├── command/                 # command pattern, undo/redo, history
│   └── spi/                     # framework-package interfaces (see ARCHITECTURE §6)
├── editor/
│   ├── canvas/                  # live IR rendering, hit-testing, overlays
│   ├── panels/                  # palette, tree, inspector
│   ├── state/                   # EditorState, selection, document session
│   └── shell/                   # window, menus, dialogs, theming
├── packages/
│   └── compose/                 # THE COMPOSE FRAMEWORK PACKAGE
│       ├── components/          # component definitions + runtime renderers
│       ├── modifiers/           # modifier definitions + renderers
│       ├── codegen/             # KotlinPoet emitters
│       └── targets/             # desktop | android | ios | wasm exporters
├── app/                         # desktop entry point, packaging config
└── samples/                     # example .vforge projects used in tests
```

**The `core` ↔ `packages/compose` boundary is the most important line in the codebase.** Anything
Compose-specific that leaks into `core/` makes Phase 5 harder. Enforce it with a Gradle dependency
rule or an architecture test (e.g. Konsist) so violations fail the build rather than relying on
discipline.

---

## 5. Data model summary

Full schema in [`DATA_MODEL.md`](DATA_MODEL.md). Summary:

- A **Project** contains screens, reusable components, a theme, and asset references.
- A **Node** is `{ id, type, props, modifiers[], children[], slots{} }`.
- **Modifiers are an ordered list**, because Compose's `Modifier` chain is order-sensitive. This is
  not an implementation detail — it is a core modeling requirement.
- **Prop values are a typed union**, not raw strings: literals, theme references, resource
  references, and (later) state bindings.
- The project file carries a `schemaVersion` and every load path runs migrations.

---

## 6. Security summary

Full detail in [`SECURITY.md`](SECURITY.md). The three risks that actually matter:

1. **Framework packages are executable code.** A package is a JAR that renders and generates code.
   Loading a third-party package is equivalent to running arbitrary software. This is the dominant
   risk in the whole design.
2. **Project files are untrusted input.** A `.vforge` file may come from anywhere. Parsing must be
   defensive; export paths must be validated against traversal.
3. **Generated code is written to disk.** Path handling and overwrite behavior must be safe and
   predictable.

---

## 7. Risks

### 7.1 Round-trip editing (highest risk — mitigated by exclusion)

Parsing hand-written Compose back into the IR is a compiler-grade problem. Arbitrary Kotlin contains
control flow, custom composables, and computed values that have no IR representation. **v1 excludes
this entirely.** The editor owns the `.vforge` file; generated `.kt` is an output artifact.

*Mitigation if users demand it later:* support a narrow, marked region (`// region ViewForge`) that
is regenerated wholesale, rather than general parsing.

*Distinct, already sanctioned (ADR-032):* re-opening a `.kt` ViewForge **itself** generated is not this
risk — the IR is carried alongside the code as a `.viewforge/project.vforge` sidecar and recognised by
the ADR-029 ownership manifest, so no Kotlin is parsed. That is "round-trip of own output", not round-trip
parsing of hand-written source, and stays fail-loud for anything outside the owned set.

### 7.2 Modifier chain combinatorics

Compose's `Modifier` API is large and order-sensitive. Supporting "all modifiers" is not achievable
or testable. *Mitigation:* a curated allowlist, plus an escape hatch for raw modifier expressions
that are passed through to codegen unvalidated and flagged in the UI as unverified.

### 7.3 Generated code quality drift

Codegen that produces compiling-but-ugly code erodes the project's core value. *Mitigation:* golden
files reviewed by a human, plus a formatting pass (ktlint/spotless) applied to output.

### 7.4 Canvas/output fidelity divergence

The interpreted canvas could diverge from compiled output. *Mitigation:* automated screenshot diff
between canvas render and compiled render as a CI gate (Phase 1 exit criterion #3).

### 7.5 Ecosystem churn

Compose Multiplatform ships frequently and has active deprecations (Navigation 3, unified `@Preview`,
v2 test APIs, dependency-alias deprecations). *Mitigation:* pin versions in the catalog, upgrade
deliberately on a schedule, never float versions.

### 7.6 Scope creep toward multi-framework

The stated long-term goal creates constant temptation to generalize early. *Mitigation:* the SPI
boundary exists from day one, but **only one implementation** (Compose) is written until Phase 5.
Abstractions get extracted against a second real implementation, never guessed.

### 7.7 Solo-developer bus factor / velocity

*Mitigation:* documentation-first workflow (this repo), ADRs for every non-obvious choice, and
tests that encode intent so context survives gaps between work sessions.

---

## 8. Milestones

| # | Milestone | Definition of done |
|---|---|---|
| M0 | Repo scaffolding | Modules, version catalog, CI, lint, this documentation set merged. |
| M1 | IR + persistence | IR data classes, `.vforge` round-trips losslessly, migration harness exists, tests pass. |
| M2 | Static canvas | A hardcoded IR tree renders as real Compose in the editor window. |
| M3 | Selection & inspection | Click-to-select with hit-testing, selection overlay, tree panel, read-only inspector. |
| M4 | Mutation & history | Add/delete/reorder/reparent via palette and drag; undo/redo correct. |
| M5 | Property editing | Inspector edits props and modifiers with typed controls; live canvas update. |
| M6 | Codegen v1 | Full component/modifier set emits compiling Kotlin; golden tests green; CI compiles output. |
| M7 | Project export | Export loose files and a runnable Gradle desktop project scaffold. |
| M8 | Theming | Theme editor for colors/typography/shapes; props can reference theme tokens. |
| M9 | **Phase 1 complete** ✅ | All Phase 1 exit criteria met; editor used to build something real. Added `Image` + `LazyColumn`/`LazyRow`, the `Gallery` sample (`samples/Gallery.vforge`), an interpreter-vs-composable pixel fidelity test (exit #3), and a lossless round-trip of the sample (exit #4). Codegen goldens + compile gate cover every component (exit #6). The Gradle export ships referenced assets so an exported project *runs* with images unmodified. Remaining follow-up: importing asset files from disk into a project (an "Assets" surface), see ADR-021. |
| M10 | Packaging ✅ | Signed installers for Windows + Linux via vanilla jpackage (`nativeDistributions` in `:app`; ADR-022, resolving open question #3 against Conveyor). Windows Msi/Exe + Linux Deb/Rpm; version single-sourced in `gradle.properties`. The Windows MSI is verified locally; Deb/Rpm build on a Linux runner. A tag-triggered `release.yml` builds per-OS, signs (Authenticode / detached GPG, gated on CI secrets — DI-1), publishes SHA-256 sums (DI-2) from the tag (DI-3), and attaches everything to a GitHub Release. Install path per OS in `_docs/INSTALL.md`. Follow-ups: branded `.ico`/`.icns` icons, runtime-module trimming (installer size), and macOS (Dmg) packaging. |
| — | **v0.1.0-alpha-1 release** | Cut `release/v0.1.0-alpha-1`, run `RELEASE_QA.md`, tag, publish the (unsigned, for the alpha) installer + checksums, back-merge to `main`. |
| M11 | Android target scaffold | Compose package gains an Android `TargetDefinition`; exporter routes `commonMain` vs `androidMain` (G9) and emits an Android Gradle + manifest scaffold; Android compile gate green in CI. |
| M12 | Android device preview | `DeviceProfiles` gains Android profiles with density + safe-area/system-bar insets; the framed canvas scales by density and draws inset chrome. |
| M13 | Responsive overrides | Schema **6 → 7** (`M6to7` + fixture; v3 taken by ADR-034, v4 by nested lists #255, v5 by component-local state #266, v6 by ADR-035 interactive state & events); node `responsive` field (ADR-030); canvas renders the active breakpoint; inspector edits per-breakpoint values. |
| M14 | Android validation + codegen | Android-specific validation warnings (touch targets, missing `contentDescription`); responsive-override codegen with golden coverage. |
| M15 | **Phase 2 complete** | All Phase-2 exit criteria met; the same project runs on Desktop and Android; canvas Android preview matches a device within tolerance. |

---

## 9. Open questions

Decide these before or during M0; record outcomes in [`DECISIONS.md`](DECISIONS.md).

1. ~~**License.**~~ **Resolved: Apache-2.0** (permissive + explicit patent grant, the best fit for a
   future third-party package ecosystem). Root [`LICENSE`](../LICENSE); © 2026 WalrusKing Software.
2. ~~**Jewel vs plain Material 3** for editor chrome.~~ **Resolved (ADR-031): stay on Material 3.**
   Jewel would force a JetBrains Runtime dependency (a build/distribution constraint) for a cosmetic
   gain; the editor chrome already runs through its own `MaterialTheme` (S3, ADR — light/dark chrome).
3. ~~**Conveyor vs vanilla Compose packaging** — evaluate cost/benefit at M10.~~ **Resolved (ADR-022):
   vanilla jpackage.** Conveyor's auto-update is a network channel excluded by ADR-011; jpackage is
   already in the Compose plugin, offline, and needs no paid product.
4. **Export model:** does ViewForge own a directory it regenerates wholesale, or write individual
   files into a user-managed project? (Recommendation: own a dedicated generated directory. It
   sidesteps merge conflicts and makes regeneration safe.)
5. **Does the editor ever invoke Gradle?** v1 recommendation: **no.** Revisit only if preview
   fidelity demands it.
6. **AI assistance in the editor** — deferred entirely from v1 planning; it adds network,
   key-management, and prompt-injection surface for no Phase 1 benefit.

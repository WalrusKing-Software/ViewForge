# Changelog

All notable changes to ViewForge are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> The `.vforge` project file carries its own `schemaVersion` (currently **2**), versioned and migrated
> independently of the application version. Feature IDs in parentheses (e.g. `C5`, `D4`, `G10`) refer to
> [`_docs/FEATURES.md`](_docs/FEATURES.md).

## [Unreleased]

_Post-0.1.0 work. Nothing released yet._

## [0.1.0] - 2026-08-14

First release — **Phase 1: Compose Desktop target.** ViewForge is a local-first, offline desktop
WYSIWYG editor that renders a UI tree with the real Compose runtime and generates idiomatic,
hand-editable Kotlin/Compose source. This release builds, edits, themes, and exports a non-trivial
Compose Desktop screen end to end.

### Added

**Canvas & rendering**
- Live rendering of the IR with the real Compose runtime (`C1`).
- Click-to-select the deepest node, hover highlight, and a selection overlay that tracks bounds through
  scroll/resize/recomposition (`C2`–`C4`).
- Pan & zoom with a single canonical content-space transform, so hit-testing stays correct at every
  zoom/pan (`C5`).
- Device preview frames from a profile registry (desktop sizes), with auto-fit-to-frame (`C6`).
- Drag-to-reparent/reorder with drop validation and visual rejection of illegal drops (`C7`).
- Per-node render error isolation — a throwing node shows an error placeholder while the rest of the
  canvas keeps working (`C8`).
- Minimum selectable affordance for empty containers (`C9`).
- Multi-select via shift/ctrl-click and a rubber-band marquee; shared prop edits apply across same-type
  nodes (`C10`).
- Static alignment guides, a hold-`M` measure/spacing overlay, and an interactive preview mode that lets
  you operate the real UI instead of editing it (`C11`–`C13`).

**Component palette**
- Categorized palette generated from the framework package's component definitions — adding a component
  needs no palette code (`P1a`).
- Drag from palette to canvas with drop-position-driven parent/index, and type-ahead search (`P2a`,
  `P3a`).
- Insert into the tree/selection, favorites (persisted) + recents, and user-defined components alongside
  built-ins (`P4a`–`P6a`).

**Tree / layers panel**
- Hierarchical tree that mirrors the IR and syncs selection bidirectionally with the canvas (`T1`).
- Drag reparent/reorder, rename, lock/hide toggles (hidden drops from render **and** codegen), keyboard
  navigation, and type-ahead search (`T2`–`T6`).

**Property inspector**
- Fully data-driven from `PropDefinition` — typed controls (color picker, Dp stepper, enum dropdown,
  bool switch, text, shape) with no per-component inspector code (`I1`, `I2`).
- Ordered modifier list editor with drag-reorder and enable toggles; theme-token binding for themeable
  props; live canvas updates with no apply button (`I3`–`I5`).
- Raw-expression escape hatch (node flagged "unverified", never evaluated), per-prop reset to default,
  and inline validation before codegen (`I6`–`I8`).

**Document & history**
- New / Open / Save / Save As with lossless `.vforge` round-trips, and a Save/Discard/Cancel prompt when
  closing with unsaved changes (`D1`).
- Undo/redo correct across mixed operations (property-based tested), with gesture coalescing so a drag or
  slider scrub is one history entry (`D2`, `D3`).
- Timer-based autosave to a config-dir sidecar with crash/quit recovery offered on next launch (`D4`).
- Copy / paste / duplicate with fresh IDs, multiple screens per project (each exports its own
  composable), and an Open Recent list (`D5`, `D6`, `D8`).
- Reusable user components — extract a selection, edit the definition in place, instances update on edit,
  with cycle detection; typed component **parameters** promoted from props and edited per instance
  (`D7`).
- Schema migration on load with a strict, forward-tolerant parser (`D9`).

**Theming**
- Modal theme editor for colors/typography/shapes/spacing with live, undoable edits (`H1`).
- Light/dark pairs with a canvas toggle, prop-to-token references, `MaterialTheme` codegen
  (`AppTheme(darkTheme, content)`), and token-rename propagation across all screens (`H2`–`H5`).

**Code generation & export**
- KotlinPoet structural codegen for every supported component and modifier, with a golden-file suite and
  a CI compile gate that actually compiles the generated output (`G1`, `G2`, `G7`).
- Live, read-only code-preview panel that follows selection (node↔code highlight + scroll, click-code to
  select), persisted visibility/width, and optional soft-wrap (`G3`).
- Export loose `.kt` files (with overwrite confirmation) or a runnable Gradle Desktop project that runs
  with `./gradlew run` unmodified; generated-file headers; copy-composable-to-clipboard (`G4`–`G6`,
  `G8`).
- Safe wholesale regeneration into a ViewForge-owned directory — replaces its own output, removes its
  orphans, and refuses to overwrite unowned files (`G10`).

**Editor shell**
- Resizable side panels with persisted layout, a File/Edit/View menu bar with discoverable shortcuts,
  and an independent light/dark editor chrome (`S1`–`S3`).
- Fuzzy command palette (`Ctrl+Shift+P`), a Preferences dialog (autosave interval, undo history depth,
  default export path), and a local-only crash reporter (`S4`–`S6`).

**Components**
- Layout: `Column`, `Row`, `Box`, `Spacer`, `Surface`, `Card`, `Scaffold` (slots), `LazyColumn`,
  `LazyRow` (static children).
- Content: `Text`, `Image`, `Icon`, `Divider`/`HorizontalDivider`.
- Input: `Button`, `OutlinedButton`, `TextButton`, `IconButton`, `TextField`, `OutlinedTextField`,
  `Checkbox`, `Switch`, `RadioButton`, `Slider`, `CircularProgressIndicator`,
  `LinearProgressIndicator`.
- Structural navigation: `TopAppBar`, `BottomAppBar`. (`NavigationBar` is Phase 2.)

**Modifiers**
- `padding`, `size`/`width`/`height`/`fillMaxWidth`/`fillMaxHeight`/`fillMaxSize`, `background`,
  `border`, `clip`, `alpha`, `weight`, `align`, `clickable`, `offset`, `aspectRatio`, `shadow`,
  `rotate`, `scale` — modeled as an **ordered** list, since order is semantic in Compose.

**Framework package system**
- SPI defined in `core/spi`; the Compose package is statically linked; an architecture test fails the
  build if `core` imports Compose (`F1`–`F3`).

**Packaging & distribution**
- Signed native installers for Windows (Msi/Exe) and Linux (Deb/Rpm) via vanilla jpackage, produced by a
  tag-triggered release workflow that publishes SHA-256 checksums (see [`_docs/INSTALL.md`](_docs/INSTALL.md)).

### Security
- Entirely offline — **no network calls** anywhere (verified empirically).
- All file writes go through a single guarded, atomic writer with path-traversal, reserved-name, and
  root-confinement checks; overwrites are confirmed.
- Untrusted `.vforge` loading enforces file-size / node-count / tree-depth limits, rejects reference
  cycles and asset paths escaping the project root, and fails loudly on a newer or malformed schema
  without partial loads.
- Generated Kotlin is emitted structurally (KotlinPoet) so hostile string values — quotes, `$`,
  `${...}`, backslashes, newlines, unicode — are escaped by construction and still compile.
- `RawExpression` values are stored, displayed, and emitted as text — never evaluated.
- `.vforge` files contain no absolute paths, usernames, or machine identifiers; crash logs stay local
  and carry no project content.

### Known limitations
- **No in-app image import.** `Image` resolves assets from the classpath (the bundled sample) and the
  inspector picker lists only assets already in the project, so `Image` cannot yet be pointed at a user's
  own file in a new project (`ADR-021`; see [`_docs/FEATURES.md`](_docs/FEATURES.md) §2).
- **Migration backup not yet wired.** Opening an older-schema file and saving does not currently write a
  `.bak` of the original, though the guarded writer supports it (tracked follow-up).
- Compose targets beyond Desktop (Android/iOS/Web) and dynamic third-party framework packages are later
  phases — see [`_docs/PROJECT_PLAN.md`](_docs/PROJECT_PLAN.md).

[Unreleased]: https://forgejo.thortower.net/WalrusKing-Software/ViewForge/compare/v0.1.0...HEAD
[0.1.0]: https://forgejo.thortower.net/WalrusKing-Software/ViewForge/releases/tag/v0.1.0

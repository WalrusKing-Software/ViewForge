# Changelog

All notable changes to ViewForge are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> The `.vforge` project file carries its own `schemaVersion` (currently **6**), versioned and migrated
> independently of the application version. Feature IDs in parentheses (e.g. `C5`, `D4`, `G10`) refer to
> [`_docs/FEATURES.md`](_docs/FEATURES.md).

## [Unreleased]

### Added

- **Screen-to-screen navigation** (ADR-039, #214) — a `Navigate` action on an event handler now generates real
  navigation: a navigating screen takes an injected `onNavigate` callback, and the exporter emits a small
  generated `App()` host that switches screens, so an exported multi-screen app connects its pages. No
  navigation dependency, no `.vforge` schema change.
- **Navigation through reusable components** (ADR-039, #324) — a `Navigate` placed inside a user component now
  works: the component's generated composable takes the same injected `onNavigate` callback and each instance
  forwards it, so a shared "nav card" or menu component can drive screen changes. Live screen switching in the
  canvas preview (#325) remains a follow-up.

## [0.2.0-alpha-1] - 2026-08-20

Second alpha of **Phase 1: Compose Desktop.** This release adds read-only **data binding** and
**interactive state & events**, a cross-project **component library**, the ability to re-open
ViewForge-generated **Kotlin**, and **macOS** packaging. The `.vforge` schema advances **2 → 6**; every
older project migrates automatically on load. Still early software — expect rough edges.

### Added

**Data binding — read-only** (ADR-034)
- Screen-level state (`Screen.state`): scalar fields (String/Int/Float/Bool) and lists of flat records,
  declared and edited with typed sample values in the inspector.
- `StateBinding` — bind a component prop to a dotted state path resolved by structural lookup, never
  evaluated; an unresolved path renders a loud placeholder instead of failing silently.
- `vforge.repeat` — a data-driven repeater that renders its template once per row of a bound list, with
  inline `forEach` and scrolling `LazyColumn` layouts.
- Populated dropdown (`vforge.dropdown`) bound to a list field, showing a chosen record field.
- Nested lists — a record may contain a list, and a repeat inside a repeat binds `item.<listField>`.
- Component-local state — reusable components carry their own `state`.
- The canvas previews the sample data (never live evaluation); codegen seeds a runnable data stub
  (generated `data class`es + `// TODO: replace with your real data source`) and emits bindings as
  structural member access.

**Interactive state & events** (ADR-035)
- State is writable at runtime; event handlers on catalog components run a closed, structured action
  model (`SetState` / `Toggle` / `Adjust` / `Navigate`, plus list mutations) dispatched by `when` — no
  expression evaluation anywhere.
- Interactive preview (run mode) operates the live UI ephemerally; the design canvas stays static.
- Codegen emits real interactive Compose (`var … by remember { mutableStateOf(…) }` + structural handler
  lambdas). `Navigate` emits a compilable `// TODO(#214)` placeholder until the nav host lands.
- A light per-project acknowledgment on first use of run mode.

**Components & reuse**
- Save a screen as a reusable palette component (#184).
- Cross-project component **library** — store, insert, drag, rename, with a transitive dependency closure
  copied into the project on use (#209, #234; ADR-033).
- Export includes every transitively-referenced component (#213).

**Import**
- Re-open ViewForge-generated `.kt` via its IR sidecar — a narrow round-trip of the editor's own output,
  not arbitrary hand-written Kotlin (#22; ADR-032).

**Editor**
- Keyboard shortcuts to toggle the four panels (Ctrl/Cmd + 1–4) (#208).
- A visual color picker (A/R/G/B channels + hex + presets) replacing the inert swatch (#293).

**Packaging & docs**
- macOS `.dmg` packaging on the `macos-latest` CI runner — installers now cover all three desktop
  platforms (Windows `.msi`/`.exe`, Linux `.deb`/`.rpm`, macOS `.dmg`). The alpha `.dmg` is
  unsigned/un-notarized and uses jpackage's default icon; Developer ID signing/notarization and a branded
  `.icns` are follow-ups (see [`_docs/INSTALL.md`](_docs/INSTALL.md)). (#291)
- `COMPATIBILITY.md` schema↔app support matrix (#292) and a root `CONTRIBUTING.md` (#210).

### Changed
- `.vforge` schema **2 → 6**, applied by automatic migrations on load: `M2to3` (data binding), `M3to4`
  (nested lists), `M4to5` (component-local state), `M5to6` (interactive state & events).

### Fixed
- Selection and hover overlays now cover `vforge.repeat` rows and template descendants, and a repeat
  selects as a single union box (#297).
- Numeric state can bind to a text prop, coerced with `.toString()` (#298).
- The inspector reliably reverts to screen-state scope and offers an explicit "← Back to <screen>"
  switch when editing a component (#299).
- Clicking or dragging the canvas clears focus from a focused inspector text field (#300).
- The tree and inspector label a component instance by its component name rather than its raw type
  (#305).

## [0.1.0-alpha-1] - 2026-08-17

First public release — an **ALPHA** of **Phase 1: Compose Desktop target.** ViewForge is a
local-first, offline desktop WYSIWYG editor that renders a UI tree with the real Compose runtime and
generates idiomatic, hand-editable Kotlin/Compose source. This alpha builds, edits, themes, and
exports a non-trivial Compose Desktop screen end to end. Early software — expect rough edges, and the
`.vforge` schema (v2) may still evolve behind migrations.

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
- Import an image from disk into a saved project — the `Image` source picker's **Import image…** copies
  the file into the project's `assets/` sidecar and points the node at it in one undoable step, hardened
  per [`_docs/SECURITY.md`](_docs/SECURITY.md) §7 (size/dimension caps reject decompression bombs, format
  sniffed from content, metadata stripped on re-encode); an unsaved document is refused with a save-first
  prompt (`#141`).

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
- Unsigned native Windows installers (`.msi`/`.exe`) via vanilla jpackage, with SHA-256 checksums
  published alongside the release (see [`_docs/INSTALL.md`](_docs/INSTALL.md)). Code-signing and Linux
  `.deb`/`.rpm` packaging are later release-engineering steps.

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
- **Unsigned alpha.** The installer is not code-signed; a downloaded build may show a Windows SmartScreen
  prompt (signing is a later release-engineering step).
- Compose targets beyond Desktop (Android/iOS/Web) and dynamic third-party framework packages are later
  phases — see [`_docs/PROJECT_PLAN.md`](_docs/PROJECT_PLAN.md).

[Unreleased]: https://github.com/WalrusKing-Software/ViewForge/compare/v0.1.0-alpha-1...HEAD
[0.1.0-alpha-1]: https://github.com/WalrusKing-Software/ViewForge/releases/tag/v0.1.0-alpha-1

# ViewForge — Release Acceptance QA

**Purpose.** A manual acceptance-test checklist to run before cutting a `release/x.y.z` branch, so we
ship with confidence that Phase-1 functionality works end to end. Derived from `FEATURES.md`
acceptance criteria, the project's correctness rules, and `SECURITY.md` §12.

**Status of this document.** Living checklist; update it each release. Test IDs are stable
(`RC-*` release-critical, then per-area). Each item maps to a `FEATURES.md` ID and/or issue #, is
tagged **[Manual]** or **[Auto]**, and is written as *precondition → steps → expected*.

**How to run.**

```bash
./gradlew allTests spotlessCheck            # all automated checks must be green first
./gradlew :packages:compose:test            # codegen golden + compile gate
./gradlew :app:run                          # the editor, for every [Manual] step
```

Toolchain: JDK 21. Most GUI / gesture / visual behaviour is **not** headless-verifiable and must be
run in the app (`:app:run`) — those are tagged **[Manual]**. Items a real automated test already
covers cite the test; gaps that *could* be automated but aren't yet are tagged
**[Auto — gap]**.

**Legend**

| Tag | Meaning |
|---|---|
| **[Auto]** | Covered by an automated test (named). Re-run, don't hand-test, unless investigating a failure. |
| **[Auto — gap]** | Could be automated and isn't; hand-test now, file a follow-up test. |
| **[Manual]** | GUI/gesture/visual; must be run in the app. |
| **P0 / P1 / P2** | FEATURES priority. P0 blocks release. |

---

## 0. Pre-flight (run before any manual testing)

| # | Check | How | Expected |
|---|---|---|---|
| PF-A | Full automated suite green | `./gradlew allTests spotlessCheck` | BUILD SUCCESSFUL, zero failures. |
| PF-B | Codegen compiles | `./gradlew :packages:compose:test` | `CompilationTest` + `CodegenEscapingTest` green. |
| PF-C | No network calls present | grep the tree for `java.net`, `openConnection`, ktor/okhttp/socket | No matches (ADR-011 / SECURITY §10). Verified empirically at audit: none present. |
| PF-D | App launches to an empty project | `./gradlew :app:run` | Window opens; palette, tree, canvas, inspector visible; no error dialog. |

---

## 1. Release-critical invariants (gate — these are no-go if they fail)

These encode the project's core correctness rules. **Any failure here blocks the release.**

### RC-1 — Never lose user work: New/Open/Save/Save As round-trip (D1, P0)
- **Precondition:** app open on a fresh project.
- **Steps:**
  1. Add a few components (Column → Text → Button). Edit some props.
  2. File → Save As…; choose a path `A.vforge`.
  3. File → New (accept discard prompt if any). Confirm a blank project.
  4. File → Open…; select `A.vforge`.
- **Expected:** the reopened project is identical to what was saved — same tree, names, props,
  modifier order, theme. **[Auto]** for the model layer (`RoundTripTest` proves save→load is the
  identity, and no field is dropped/reordered); **[Manual]** for the file-dialog wiring.

### RC-2 — Prompt-on-close when dirty (D1 / #56, P0)
- **Precondition:** a project with unsaved edits (title shows the unsaved •).
- **Steps:** click the window close button.
- **Expected:** a **Save / Discard / Cancel** dialog. *Save* writes then closes (opens Save As for a
  never-saved doc; if Save As is cancelled or the write fails, the app stays open — work not lost).
  *Discard* closes. *Cancel* returns to the editor. **[Manual]** (window lifecycle + native chooser;
  the underlying save flow is `RoundTripTest`-covered).

### RC-3 — Autosave + crash recovery (D4 / #54, P0)
- **Precondition:** Preferences autosave interval at default (10s). Make an edit and **do not save**.
- **Steps:**
  1. Wait past the autosave interval, then hard-kill the app (kill the process, not a clean exit).
  2. Relaunch `:app:run`.
- **Expected:** a **Restore / Discard** dialog offers the recovered work; Restore brings back the
  unsaved edits (marked dirty). A clean quit-without-save also leaves a recoverable snapshot.
  **[Manual]** (timer + real crash); recovery store round-trip/total-load is **[Auto]**
  (`RecoveryStoreTest`).

### RC-4 — Atomic, guarded, path-safe writes (SECURITY §5, P0)
- **Expected (all [Auto], `PersistenceSafetyTest`):** writes are atomic (no `.tmp` left behind);
  reserved Windows device names (`CON`, …), trailing dot/space names, and any path outside the chosen
  root are rejected; backup copies prior content when requested.
- **[Manual] spot-check:** Export → .kt into a directory; interrupt is not testable, but confirm no
  partial/zero-byte files remain after a normal export.

### RC-5 — Codegen emits Kotlin that compiles, modifier order preserved (G1/G2 + rule 2/4, P0)
- **Expected (all [Auto]):** `GoldenCodegenTest` (IR→exact `.kt` per component/modifier, incl. a
  modifier-order permutation `ModifierOrder`), `CompilationTest` (every golden compiles against real
  Compose), `CodegenEscapingTest` (hostile string values — quotes/`$`/`${...}`/backslash/newline/
  unicode — still compile: GC-2), `ModifierOrderTest`. Codegen uses KotlinPoet's structural API
  (rule 4) — no string concatenation.
- **[Manual] end-to-end:** see smoke step S-8 (export the Gallery sample and compile it).

### RC-6 — Undo/redo integrity across mixed operations (D2/D3, P0)
- **Precondition:** a project with several nodes.
- **Steps:** perform ≥10 mixed edits (add, delete, move/reparent, prop edit, modifier reorder,
  duplicate, rename, theme edit). Undo all the way to empty, then redo all the way back.
- **Expected:** the document returns to each intermediate state exactly; final redo equals the
  pre-undo document. A drag / slider scrub is **one** undo entry, not hundreds. **[Auto]**
  (`UndoRedoPropertyTest` is the property-based ≥50-op check; `HistoryTest`, `CommandTest`);
  **[Manual]** confirmation of gesture coalescing (D3) on a live drag/scrub.

### RC-7 — Fail loud, never render wrong (C8 + ARCHITECTURE §9, P0)
- **Expected:** a node that throws during render shows an **error placeholder**; the rest of the
  canvas keeps working (C8). Unknown component/modifier types render an explicit "unknown" placeholder
  (PF-6). A `RawExpression` prop shows a placeholder and marks the node **unverified** — never
  evaluated (PF-4). **[Manual]** for the visuals; **[Auto]** for unknown-type/resolution logic
  (`UserComponentResolutionTest`, `NodeDisplayTest`).

### RC-8 — Hostile / newer / corrupt `.vforge` fails safely (D9 + SECURITY §3, P0)
- **Expected (all [Auto], `MigrationTest` + `PersistenceSafetyTest`):** a **newer** schema →
  `NEWER_SCHEMA` failure with a clear message (never a partial load); a missing `schemaVersion` →
  `MISSING_VERSION`; malformed JSON → `MALFORMED`; a depth-bomb / huge-node-count / cyclic-component /
  traversal-in-asset-path file → rejected with a specific message; the original file is never
  overwritten on a failed load (PF-7).
- **[Manual] spot-check:** hand-edit a copy of a `.vforge` to `"schemaVersion": 999`, Open it →
  clear error dialog, editor stays usable.

> ✅ **Resolved (was Gap G-2, issue #142):** opening an older-schema `.vforge` now flags the first save
> to back up the original — `ProjectStore.load` reports the pre-migration version, `EditorState` carries
> a `backupOnNextSave` flag, and `DocumentController` passes `backup=true` on that save, writing a
> `<name>.bak` of the untouched original before the migrated form replaces it. FEATURES D9 is now met.
> **[Auto]:** `MigrationTest` (version reporting + end-to-end `.bak`), `DocumentSessionTest`
> (`backupOnNextSave` lifecycle). **[Manual] spot-check:** open the committed schema-1 `Demo.vforge`,
> Save over it, confirm a `Demo.vforge.bak` holding the original appears.

---

## 2. Canvas & rendering (FEATURES §1)

| ID | Test | Precondition → Steps → Expected | Tag |
|---|---|---|---|
| CV-1 (C1) | Live IR render | Add nested Column/Row/Text/Button → they render with real Compose, no visual diff vs output. | [Manual] |
| CV-2 (C2) | Click-to-select deepest | Click a nested Text → the innermost node is selected and outlined, not its parent. | [Manual] |
| CV-3 (C3) | Hover highlight | Hover a node → distinct outline appears, layout does not shift. | [Manual] |
| CV-4 (C4) | Selection tracks bounds | Select a node, resize the window / scroll a list → outline stays on the node. | [Manual] |
| CV-5 (C5) | Pan & zoom + correct hit-testing | Scroll to zoom; hold **Space** and drag to pan; `Ctrl +/−/0`. Then click a node at, e.g., 200% zoom. | Selection lands on the clicked node at every zoom/pan (the #116 content-space fix). Pure transform math is [Auto] (`CanvasTransformTest`, `HitTestTest`); zoom-correct click is [Manual]. |
| CV-6 (C6) | Device frames + fit | Toolbar device dropdown → pick 1920×1080; `Ctrl+9` / View → Fit to Frame. | Canvas frames the root to the profile size (centered, clipped); Fit sets zoom so the whole frame shows. Persists per-screen (undoable). [Auto] registry/fit math (`DevicePreviewTest`, `CanvasViewportTest`); [Manual] visual. |
| CV-7 (C7) | Drag to reparent/reorder | Drag a node onto a container → green caret + target outline; drag into a non-container or into its own descendant → red/reject. | [Manual]; drop logic [Auto] (`CanvasDropTest`). |
| CV-8 (C8) | Per-node error isolation | (see RC-7) | [Manual] |
| CV-9 (C9) | Empty-container affordance | Add an empty Column → it still shows a minimum selectable target. | [Manual]; `ContainerNodesTest` covers the model side [Auto]. |
| CV-10 (C10) | Multi-select | Shift/Ctrl-click several nodes; also rubber-band marquee on empty canvas. Edit a shared prop. | All selected; a shared prop edit applies to every **same-type** node with an "N selected" banner. [Auto] selection/marquee model (`SelectionModelTest`, `MarqueeTest`, `SharedEditTest`, `BatchOperationsTest`); [Manual] gestures. |
| CV-11 (C11) | Alignment guides | View → Alignment guides; select a node whose edge lines up with a sibling/parent. | A guide line appears at the aligned edge/centre. [Auto] `AlignmentGuidesTest`; [Manual] visual. (Free-move snapping is deferred #129.) |
| CV-12 (C12) | Measure overlay | Hold **M** over the canvas with a node selected. | Gaps to the parent's inner edges (and siblings, #127) shown in dp. [Auto] `MeasureGapsTest`; [Manual] gesture. |
| CV-13 (C13) | Interactive preview | View → Interactive preview → click a Button / type in a TextField / drag a Slider. | The real UI responds (ripples, editable inputs); selection overlay is off; leaving preview restores outlines. Codegen unaffected (`FidelityTest` still green). [Manual]. |

---

## 3. Component palette (FEATURES §2)

| ID | Test | Precondition → Steps → Expected | Tag |
|---|---|---|---|
| PL-1 (P1a) | Categorized from registry | Open palette → components grouped by category, generated from the package (no per-component UI). | [Manual]; `CatalogConsistencyTest` locks catalog/renderer/emitter parity [Auto]. |
| PL-2 (P2a) | Drag to canvas | Drag a component from palette onto a container → drops at the pointer's parent+index. | [Manual]. |
| PL-3 (P3a) | Search/filter | Type in the palette search → filters by name/category, type-ahead. | [Manual]. |
| PL-4 (P4a) | Insert via tree/selection | With a container selected, click a palette row → inserts into the selection. | [Manual]. |
| PL-5 (P5a) | Favorites / recent | Clear the search → ★ Favorites + Recent sections show; star a row to pin. | Favorites persist across sessions; recents are session-only. [Auto] `FavoriteComponentsTest`, `PaletteFavoritesTest`; [Manual] gesture. |
| PL-6 (P6a) | User components in palette | Extract a selection (Edit → Extract to Component) → it appears under "Components". | [Manual]; `EditorComponentsTest` [Auto]. |
| PL-7 | **Image component usability** | Add an `Image` to a **new** project → open the inspector source dropdown. | ⚠️ **Known gap G-1 (issue #141):** the dropdown only lists **already-imported** assets and there is **no disk-import UI**; a fresh project has none, so Image cannot be pointed at a user file. Works only with the bundled Gallery sample's classpath assets. [Manual]. |

> **Component coverage:** the full Phase-1 set (FEATURES §2 — Layout/Content/Input + structural
> TopAppBar/BottomAppBar) each has a renderer + emitter + golden triple; `NavigationBar` is
> intentionally Phase-2. Spot-check that each palette entry inserts and renders. [Auto] goldens per
> component.

---

## 4. Tree / layers panel (FEATURES §3)

| ID | Test | Precondition → Steps → Expected | Tag |
|---|---|---|---|
| TR-1 (T1) | Hierarchical tree | Tree mirrors the IR; expand/collapse; selecting in the tree selects on the canvas and vice-versa. | [Manual]. |
| TR-2 (T2) | Drag reparent/reorder | Same drop rules as the canvas (reject cycles/non-containers). | [Manual]; `CanvasDropTest` shares the logic [Auto]. |
| TR-3 (T3) | Rename | Double-click / F2 → edit `Node.name`; shown in tree; codegen structure unaffected. | [Manual]. |
| TR-4 (T4) | Lock / hide | Toggle `locked` → node shows a padlock badge on the canvas + a tree marker and can't be selected, dragged, dropped-into, or renamed (per-node; a container nested inside it still accepts drops); render/codegen unaffected. Toggle `hidden` → removed from render **and** codegen. | [Manual] for the canvas/tree gestures; `canDrop`/`renameNode`/`canvasDropTarget`/`lockedNodes` logic is [Auto]; hidden-drops-from-codegen is [Auto] via goldens. |
| TR-5 (T5) | Keyboard nav | Focus the tree → ↑/↓ move selection (skipping locked), ←/→ collapse/expand/step, Enter rename, Del remove. | [Auto] decisions (`TreeKeyNavTest`); [Manual] focus/key wiring. |
| TR-6 (T6) | Search within tree | Type in the layers search → filters to matching nodes keeping ancestor path; no-match message. | [Auto] `TreeSearchTest`; [Manual] gesture. |
| TR-7 (#160) | Right-click context menu | Right-click a node in the tree or on the canvas → selects it and opens a menu (Cut/Copy/Paste/Duplicate/Delete/Rename/Extract to Component/Enter Component) with correct enablement; a locked node opens none. | [Auto] enablement (`ContextMenuTest`); [Manual] the right-click gesture + menu positioning. |

---

## 5. Property inspector (FEATURES §4)

| ID | Test | Precondition → Steps → Expected | Tag |
|---|---|---|---|
| IN-1 (I1/I2) | Data-driven typed controls | Select nodes of different types → controls (color picker, Dp stepper, enum dropdown, bool switch, text, shape) are generated from `PropDefinition`; **no per-component UI**. | [Manual]; `PropEditingTest`, `NodeDisplayTest` [Auto]. |
| IN-2 (I3) | Ordered modifier editor | Add modifiers; **drag to reorder**; toggle-enable one. | Order visibly changes the render and the generated modifier chain. [Manual]; order semantics [Auto] (`ModifierOrderTest` + golden). |
| IN-3 (I4/H3) | Theme token picker | Bind a color/typography/shape prop to a token instead of a literal. | Prop shows the token; canvas uses the theme value. [Manual]; `ThemeCodegenTest` [Auto]. |
| IN-4 (I5) | Live update | Edit any prop → canvas updates within a frame; no apply button. | [Manual]. |
| IN-5 (I6) | Raw expression escape hatch | Set a prop to a raw expression → node flagged **unverified**, canvas shows a placeholder, never evaluated. | [Manual] (PF-4/GC-4). |
| IN-6 (I7) | Reset to default | Edit a prop, then per-prop revert → returns to default. | [Manual]. |
| IN-7 (I8) | Validation feedback | Enter an invalid value (e.g. a bad number / illegal screen name) → inline error before codegen. | [Manual]; `PreferencesValidationTest`, screen-name validation [Auto] (`ScreenSessionTest`). |

---

## 6. Document & history (FEATURES §5)

Covered largely by §1 (RC-1…RC-3, RC-6, RC-8). Additional area-specific items:

| ID | Test | Precondition → Steps → Expected | Tag |
|---|---|---|---|
| DC-1 (D5) | Copy / paste / duplicate | Copy a node, paste → fresh IDs, targets current selection; Duplicate likewise; multi-node clipboard as one undo. | [Auto] (`EditorStateTest`, `BatchOperationsTest`); [Manual] menu/shortcut. |
| DC-2 (D6) | Multiple screens | Add screens via the switcher; rename; switch; each exports its own composable. | [Manual]; `ScreenCommandTest`, `ScreenSessionTest` [Auto]. |
| DC-3 (D7) | Reusable components + edit-in-place | Extract → component; edit the definition (double-click to enter) → every instance updates; a cycle-forming insert is refused (greyed + tooltip). | [Manual]; `ComponentGraphTest`, `CycleGuardTest`, `EditInPlaceTest`, render/codegen goldens [Auto]. |
| DC-4 (D7/params) | Component parameters | Promote a prop to a parameter; set per-instance args in the inspector → codegen emits typed fn params + call args, canvas resolves. | [Manual]; `ParameterCommandTest`, `ParameterTypeTest`, `ParameterEditTest`, `ParameterBindingTest`, `ParameterTypesTest` + goldens [Auto]. |
| DC-5 (D8) | Recent projects | Open/Save a couple of projects → File → Open Recent lists them, most-recent-first; a stale path reports + drops. | [Manual]; `RecentProjectsTest`, `RecentProjectsStateTest` [Auto]. |
| DC-6 (D9) | Migration on load + backup | Open the committed schema-1 `Demo.vforge` → migrates to current and loads; saving over it writes a `.bak` of the original first. | [Auto] (`MigrationTest` migrates the real committed fixture **and** asserts the backup-on-migrate save; `DocumentSessionTest` covers the flag lifecycle). Backup-on-migrate gap **G-2** fixed (#142). |

---

## 7. Theming (FEATURES §6)

| ID | Test | Precondition → Steps → Expected | Tag |
|---|---|---|---|
| TH-1 (H1) | Theme editor | View → Theme… → edit colors/typography/shapes/spacing → canvas updates live; each edit undoable. | [Manual]; `ThemeCommandTest`, `ThemeEditTest` [Auto]. |
| TH-2 (H2) | Light/dark preview | View → Dark canvas toggle → canvas swaps between light/dark; both values stored. | [Manual]. |
| TH-3 (H4) | Theme codegen | Export → generated `Theme.kt` with `AppTheme(darkTheme, content)`; Main wraps the screen in it. | [Auto] (`ThemeCodegenTest`, `CompilationTest` theme case); [Manual] spot-check. |
| TH-4 (H5) | Token rename propagation | Rename a theme token → every `ThemeRef` across all screens updates in one undoable step. | [Manual]; command-level [Auto]. |

---

## 8. Codegen / export (FEATURES §7)

| ID | Test | Precondition → Steps → Expected | Tag |
|---|---|---|---|
| EX-1 (G1/G2/G7) | Generate + compile + format | (see RC-5). | [Auto]. |
| EX-2 (G3) | Live code preview | View → Code preview → panel shows the active screen's Kotlin, updates on edit; selecting a node scrolls to + highlights its code; clicking code selects the node. | [Manual]; span mapping [Auto] (`SourceSpansTest`, `CodeSpanLookupTest`, `CodePreviewContentTest`). |
| EX-3 (G4) | Export loose `.kt` | File → Export → .kt files → choose a dir; if files exist, an overwrite confirmation lists them. | Files written atomically; overwrite requires confirm (FW-5). [Manual]; `ProjectExporterTest`, `DesktopExporterTest` [Auto]. |
| EX-4 (G5) | Export runnable Gradle project | File → Export → Gradle project → in a shell, `./gradlew run` in the output. | The exported project runs unmodified and shows the screen. [Auto] scaffold (`ExportSampleTest`); [Manual] the actual `gradlew run`. |
| EX-5 (G6) | Generated header | Inspect any exported `.kt`. | Header names the source `.vforge`, schema version, and warns about hand-edits. [Auto] via goldens. |
| EX-6 (G8) | Copy composable to clipboard | Code preview panel → Copy action → paste elsewhere. | The active screen's composable is on the clipboard. [Manual] (clipboard is GUI-only). |
| EX-7 (G10) | Safe regeneration | File → Regenerate Gradle project → into a previously-exported dir; then add a hand-written file and regenerate again. | ViewForge replaces its own files + removes its orphans, but **refuses** to overwrite unowned files (reports them). [Auto] (`RegenerationPlanTest`, `RegenerationTest`); [Manual] dialogs. |

---

## 9. Editor shell / menus / shortcuts (FEATURES §8)

| ID | Test | Precondition → Steps → Expected | Tag |
|---|---|---|---|
| SH-1 (S1) | Resizable + persisted panels | Drag panel dividers; toggle panels via View menu; restart the app. | Widths + visibility persist across sessions. [Auto] `PanelLayoutTest`, `PanelVisibilityTest`, `PreferencesStoreTest`; [Manual] drag. |
| SH-2 (S2) | Keyboard shortcuts | Exercise: `Ctrl+N/O/S/Shift+S`, `Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y`, `Ctrl+X/C/V/D`, `Del`/`Backspace`, `F2`, `Ctrl +/−/0`, `Ctrl+9`, `Ctrl+Shift+P`, hold **Space** (pan), hold **M** (measure). | Each fires the documented action; shortcuts are shown in menu labels (discoverable). Focus-aware: typing in a rename/search field is not hijacked. [Manual]; `AppMenuBarTest` gates menu enablement [Auto]. |
| SH-3 (S3) | Editor light/dark chrome | View → Dark editor → chrome theme flips, independent of the project's canvas theme; persists. | [Manual]; `PreferencesStoreTest` chrome round-trip + `EditorStateTest` [Auto]. |
| SH-4 (S4) | Command palette | `Ctrl+Shift+P` → fuzzy launcher over File/Edit/View + Go-to-screen + Add-component; disabled commands greyed; ↑/↓/Enter/Esc. | [Auto] ranking (`CommandPaletteTest`); [Manual] popup. |
| SH-5 (S5) | Preferences dialog | File → Preferences… → edit autosave interval, undo history depth, default export path; enter an invalid number. | Invalid input blocks Save inline (I8); valid values persist and take effect live (timer re-keys, history trims). [Auto] `PreferencesValidationTest`, `HistoryTest`, `PreferencesStoreTest`; [Manual] dialog/native picker. |
| SH-6 (S6) | Local crash reporter | (see also RC-3) Force an uncaught exception path if feasible. | A crash log is written locally under the config dir; **no network transmission**; log carries only a stacktrace + thread name, **no project content** (PR-2/PR-3). [Auto] `CrashReporterTest`; [Manual] the uncaught-handler install. |

---

## 10. Framework package boundary (FEATURES §9)

| ID | Test | Expected | Tag |
|---|---|---|---|
| FW-1 (F1/F3) | `core` has no Compose dependency | The architecture boundary holds; build fails if `core` imports Compose. | [Auto] — enforced by the build/convention plugin. Confirm the guard actually fails on a violation (spot-check by temporarily adding a Compose import to a `core` file in a scratch branch — do not commit). |
| FW-2 (F2) | Static registration | The Compose package is compile-time wired; no dynamic loading present (SECURITY §4). | [Auto]/[Manual] — verify no `URLClassLoader`/`ServiceLoader` package loading exists. |

---

## 11. Security pre-release checklist (SECURITY §12)

| # | Item | Status |
|---|---|---|
| SEC-1 | No dynamic version ranges in `libs.versions.toml` | [Manual] — grep for `+` / `latest` in the catalog. |
| SEC-2 | Path-traversal export tests pass (incl. Windows forms) | [Auto] `PersistenceSafetyTest`. |
| SEC-3 | Malformed/hostile `.vforge` fails safely (depth bomb, huge count, cycles, asset traversal) | [Auto] `PersistenceSafetyTest`, `MigrationTest`. |
| SEC-4 | Generated-code escaping (quotes, `$`, newlines, unicode) | [Auto] `CodegenEscapingTest` (added for this release). |
| SEC-5 | No absolute paths / usernames in a saved `.vforge` (PR-4) | [Auto] `RoundTripTest`. |
| SEC-6 | No network calls present (verify empirically) | [Auto/Manual] grep — none present. |
| SEC-7 | Crash logs contain no project content | [Auto] `CrashReporterTest`. |
| SEC-8 | Release artifacts signed; checksums published | ⛔ **Release-engineering, not verified here** — must be handled by the `release/*` process. |
| SEC-9 | Gradle wrapper checksum verified / dependency verification committed | [Manual] — confirm before release (DS-2/DS-5). |

---

## 12. Smoke test (must-pass gate — run every one before tagging)

Run in `:app:run`. If any step fails, **no-go**.

1. **Create** — launch to a blank project; add a Column, then a Text and a Button inside it (via
   palette click **and** drag-from-palette). *Expected:* both insert where dropped and render.
2. **Edit** — change the Text's `text`, `fontSize`, `color` (bind color to a theme token); add a
   `padding` then a `background` modifier and **reorder** them. *Expected:* canvas updates live; the
   modifier reorder visibly changes the render.
3. **Theme** — View → Theme…, change `primary`; toggle **Dark canvas**. *Expected:* live update; the
   token-bound Text tracks the change.
4. **Multi-select** — Shift-click two same-type nodes, edit a shared prop. *Expected:* applies to
   both with an "N selected" banner.
5. **Save** — File → Save As… to `smoke.vforge`. *Expected:* title clears the unsaved •.
6. **Reopen** — File → New, then File → Open `smoke.vforge`. *Expected:* identical to what was saved.
7. **Undo/redo** — undo the last several edits to an earlier state, then redo. *Expected:* returns
   exactly; drag/scrub were single entries.
8. **Export + compile** — File → Export → Gradle project; in a shell run `./gradlew run` in the
   output. *Expected:* it compiles and runs, showing the screen. (Or export loose `.kt` and compile.)
9. **Device profile + zoom** — switch the device dropdown to 1920×1080; zoom to ~200% and pan with
   Space-drag; click a node. *Expected:* the frame reshapes; the selection outline lands exactly on
   the clicked node at zoom (no offset).
10. **Code preview** — View → Code preview; select a node. *Expected:* the panel highlights + scrolls
    to that node's code; clicking code selects the node.
11. **Corrupt/newer file** — Open a copy of a `.vforge` hand-edited to `"schemaVersion": 999`.
    *Expected:* a clear error dialog; the editor stays usable; the file is untouched.
12. **Close-when-dirty** — make an edit and close the window. *Expected:* Save/Discard/Cancel prompt
    behaves correctly.

---

## 13. Release-readiness summary

### Gaps found during the audit (file as issues before release)

| ID | Severity | Gap | Status / recommendation |
|---|---|---|---|
| **G-1** | Medium (feature completeness) | **Image asset disk-import not implemented.** `AssetImageLoader` resolves assets from the **classpath only** (bundled sample); no UI copies a user file into the project, and the inspector source dropdown only lists already-imported assets. So `Image` is not usable in a user-created project. | **Filed as issue #141.** Known ADR-021 follow-up; FEATURES already carries the v0.1.0 caveat and PL-7 documents it. Either (a) ship with the documented limitation, or (b) implement import (AS-1…AS-5: size/pixel caps, type sniffing, EXIF strip, copy-into-project). Recommend at least (a) for this release. |
| **G-2** | Medium (never-lose-work / D9) | **Migrate-with-backup not wired.** No caller passed `backup=true`, so an older-schema file migrated on load and then saved was overwritten with **no `.bak`**, contradicting FEATURES D9. | ✅ **Fixed (issue #142).** `ProjectStore.load` now reports the pre-migration version; `EditorState.backupOnNextSave` carries it; `DocumentController` requests `backup=true` on the first save after a migrating open. Covered by `MigrationTest` + `DocumentSessionTest`. |
| **G-3** | Doc-hygiene (non-blocking) | `memory/MEMORY.md` and `catalog-props-progress.md` predate #118–#123 / #127 / #137 (now merged); FEATURES.md is current. | Assistant-memory hygiene, **not** a project-tracker item (deliberately not filed in Forgejo). Reconciled this pass. Audit was performed against code + FEATURES, not memory. |

### Automatable coverage added this pass
- `CodegenEscapingTest` + `HostileStrings.vforge` — compile-verified string escaping (SEC-4 / GC-2),
  a SECURITY §12 checklist item that had no dedicated test. Green.
- **G-2 fix (#142):** `MigrationTest` now asserts `LoadResult.Success.migratedFromVersion` reporting
  (older file → source version; current file → null) **and** the end-to-end backup — loading the
  committed schema-1 `Demo.vforge` then saving writes a byte-identical `Demo.vforge.bak` of the original.
  `DocumentSessionTest` covers the `EditorState.backupOnNextSave` lifecycle (set on migrated open, cleared
  on save/new). Green.

### Automatable coverage confirmed already present (no work needed)
Guarded-writer safety (reserved names/trailing dot/outside-root/atomic/backup), depth/node/asset/
cycle validation, newer/missing/malformed schema handling, migration chaining against the real
committed fixture, PR-4 no-absolute-paths, round-trip idempotence, all `PropValue` kinds incl.
`ParamRef`, undo/redo property test, codegen goldens + compile gate, span mapping, recovery
round-trip, regeneration planning.

### Go / No-Go

**No-go if any of these fail:**
- [ ] Pre-flight PF-A…PF-D green (`allTests spotlessCheck`, app launches).
- [ ] RC-1…RC-8 all pass (never-lose-work, close-guard, recovery, guarded writes, codegen compiles +
      modifier order, undo/redo, fail-loud, hostile-file safety).
- [ ] Smoke test steps 1–12 all pass.
- [ ] SEC-1…SEC-7 verified; SEC-8/SEC-9 handled by release engineering.

**Go with documented limitations (not blockers, but decide explicitly):**
- [ ] **G-1** (#141) Image import: ship with the documented limitation, or implement before release.
- [x] **G-2** (#142) migrate-with-backup: **fixed** — migrated files now back up the original on first save.
- [ ] `NavigationBar` is intentionally Phase-2 (not a gap).
- [ ] P2/P3 features (I9 responsive, H6 theme import, F4–F6 dynamic packages) are out of scope for
      Phase 1.

**Decision:** _record go / no-go, date, and sign-off here per release._

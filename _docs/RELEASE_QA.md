# ViewForge — Release Acceptance QA

**Purpose.** A manual acceptance-test plan to run before cutting a `release/x.y.z` branch, so we ship
with confidence that Phase-1 functionality works end to end. Derived from `FEATURES.md` acceptance
criteria, the project's correctness rules, and `SECURITY.md` §12.

**Status of this document.** Living plan; update it each release. Test IDs are stable (`RC-*`
release-critical, then per-area). Each item maps to a `FEATURES.md` ID and/or issue #, is tagged
**[Manual]** or **[Auto]**, and is written so a **non-developer can run it top-to-bottom**.

**How to read an item.** Every feature under test is a short entry with the same shape:

- **Steps** — numbered, literal actions ("click here, type this"). No developer knowledge assumed.
- **Pass** — exactly what a passing result looks like on screen.
- **If it fails** — the most likely cause and the fix/where-to-look, when known (drawn from this
  project's real bug history). "None known" means no recurring failure has been seen.
- **Automated** — the test that already proves the logic, where one exists; re-run it rather than
  hand-testing unless you are chasing a failure.

**How to run.**

```bash
./gradlew allTests spotlessCheck            # all automated checks must be green first
./gradlew :packages:compose:test            # codegen golden + compile gate
./gradlew :app:run                          # the editor, for every [Manual] step
```

Toolchain: JDK 21. Most GUI / gesture / visual behaviour is **not** headless-verifiable and must be
run in the app (`:app:run`) — those are tagged **[Manual]**. Items a real automated test already
covers cite the test; gaps that *could* be automated but aren't yet are tagged **[Auto — gap]**.

**Legend**

| Tag | Meaning |
|---|---|
| **[Auto]** | Covered by an automated test (named). Re-run, don't hand-test, unless investigating a failure. |
| **[Auto — gap]** | Could be automated and isn't; hand-test now, file a follow-up test. |
| **[Manual]** | GUI/gesture/visual; must be run in the app. |
| **P0 / P1 / P2** | FEATURES priority. P0 blocks release. |

**Vocabulary for a non-developer tester.**

- **Node / component** — one box in the design (a Text, a Button, a Column). The **tree** (left,
  "Layers") lists them; the **canvas** (centre) draws them; the **inspector** (right) edits the
  selected one.
- **Modifier** — a stackable tweak on a node (padding, background, size). Their **order matters** and
  is shown top-to-bottom in the inspector.
- **The unsaved dot (•)** — appears in the window title when you have changes not yet saved.
- **`.vforge` file** — a saved ViewForge project.

---

## 0. Pre-flight (run before any manual testing)

| # | Check | How | Expected |
|---|---|---|---|
| PF-A | Full automated suite green | `./gradlew allTests spotlessCheck` | BUILD SUCCESSFUL, zero failures. |
| PF-B | Codegen compiles | `./gradlew :packages:compose:test` | `CompilationTest` + `CodegenEscapingTest` green. |
| PF-C | No network calls present | grep the tree for `java.net`, `openConnection`, ktor/okhttp/socket | No matches (ADR-011 / SECURITY §10). Verified empirically at audit: none present. |
| PF-D | App launches to an empty project | `./gradlew :app:run` | Window opens; palette, tree, canvas, inspector visible; no error dialog. |

If **PF-A** or **PF-B** is red, stop — fix or investigate before any manual testing; a red suite
invalidates the run.

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
- **Pass:** the reopened project is identical to what was saved — same tree, names, props, modifier
  order, theme.
- **If it fails:** a prop or modifier is missing/reordered → suspect a serialization gap, but the
  model round-trip is proven by `RoundTripTest`, so first suspect the **file-dialog wiring** (wrong
  path, silent Save-As cancel). A dialog never appears → see RC-2's focus note.
- **Automated:** `RoundTripTest` (save→load is the identity, no field dropped/reordered). **[Manual]**
  for the file-dialog wiring only.

### RC-2 — Prompt-on-close when dirty (D1 / #56, P0)
- **Precondition:** a project with unsaved edits (title shows the unsaved •).
- **Steps:** click the window close button.
- **Pass:** a **Save / Discard / Cancel** dialog. *Save* writes then closes (opens Save As for a
  never-saved doc; if Save As is cancelled or the write fails, the app stays open — work not lost).
  *Discard* closes. *Cancel* returns to the editor.
- **If it fails:** window closes with no prompt → the dirty flag isn't set; confirm the • shows after
  an edit. Prompt appears but Save silently loses work → check that a never-saved doc opens Save-As
  rather than writing to an empty path.
- **Automated:** the underlying save flow is `RoundTripTest`-covered. **[Manual]** (window lifecycle +
  native chooser).

### RC-3 — Autosave + crash recovery (D4 / #54, P0)
- **Precondition:** Preferences autosave interval at default (10s). Make an edit and **do not save**.
- **Steps:**
  1. Wait past the autosave interval, then hard-kill the app (kill the process, not a clean exit).
  2. Relaunch `:app:run`.
- **Pass:** a **Restore / Discard** dialog offers the recovered work; Restore brings back the unsaved
  edits (marked dirty). A clean quit-without-save also leaves a recoverable snapshot.
- **If it fails:** no Restore dialog after a crash → the snapshot never wrote; confirm you waited past
  the interval and that the edit left the doc dirty. Restore brings back stale/older state → the
  snapshot clears only on a clean save, so this usually means the kill raced an autosave tick.
- **Automated:** recovery store round-trip/total-load is `RecoveryStoreTest`. **[Manual]** (timer +
  real crash).

### RC-4 — Atomic, guarded, path-safe writes (SECURITY §5, P0)
- **Pass (all [Auto], `PersistenceSafetyTest`):** writes are atomic (no `.tmp` left behind); reserved
  Windows device names (`CON`, …), trailing dot/space names, and any path outside the chosen root are
  rejected; backup copies prior content when requested.
- **If it fails:** a rejected name slips through or a `.tmp` remains → a guarded-writer regression;
  path validation lives in one place (`core/project`), fix it there, not at the call site.
- **[Manual] spot-check:** Export → .kt into a directory; confirm no partial/zero-byte files remain
  after a normal export.

### RC-5 — Codegen emits Kotlin that compiles, modifier order preserved (G1/G2 + rule 2/4, P0)
- **Pass (all [Auto]):** `GoldenCodegenTest` (IR→exact `.kt` per component/modifier, incl. a
  modifier-order permutation `ModifierOrder`), `CompilationTest` (every golden compiles against real
  Compose), `CodegenEscapingTest` (hostile string values — quotes/`$`/`${...}`/backslash/newline/
  unicode — still compile: GC-2), `ModifierOrderTest`. Codegen uses KotlinPoet's structural API
  (rule 4) — no string concatenation.
- **If it fails:** a golden mismatches but the code looks fine → the golden is the contract;
  regenerate only deliberately. Emitted code doesn't compile → `CompilationTest` will catch it; never
  trust string-equality alone.
- **[Manual] end-to-end:** see smoke step S-8 (export the Gallery sample and compile it).

### RC-6 — Undo/redo integrity across mixed operations (D2/D3, P0)
- **Precondition:** a project with several nodes.
- **Steps:** perform ≥10 mixed edits (add, delete, move/reparent, prop edit, modifier reorder,
  duplicate, rename, theme edit). Undo all the way to empty, then redo all the way back.
- **Pass:** the document returns to each intermediate state exactly; final redo equals the pre-undo
  document. A drag / slider scrub is **one** undo entry, not hundreds.
- **If it fails:** undo overshoots/undershoots a drag → gesture coalescing (D3) broke; a scrub must
  collapse to one entry. Undo restores the wrong state → a command's `invert` is wrong; the property
  test should have caught it.
- **Automated:** `UndoRedoPropertyTest` (property-based ≥50-op check), `HistoryTest`, `CommandTest`.
  **[Manual]** confirmation of gesture coalescing (D3) on a live drag/scrub.

### RC-7 — Fail loud, never render wrong (C8 + ARCHITECTURE §9, P0)
- **Pass:** a node that throws during render shows an **error placeholder**; the rest of the canvas
  keeps working (C8). Unknown component/modifier types render an explicit "unknown" placeholder
  (PF-6). A `RawExpression` prop shows a placeholder and marks the node **unverified** — never
  evaluated (PF-4).
- **If it fails:** a broken node blanks the whole canvas → error isolation regressed (must be
  per-node). A raw expression renders a real value → it was evaluated; that is a **security** bug,
  raw expressions are text only, never executed.
- **Automated:** unknown-type/resolution logic (`UserComponentResolutionTest`, `NodeDisplayTest`).
  **[Manual]** for the visuals.

### RC-8 — Hostile / newer / corrupt `.vforge` fails safely (D9 + SECURITY §3, P0)
- **Pass (all [Auto], `MigrationTest` + `PersistenceSafetyTest`):** a **newer** schema →
  `NEWER_SCHEMA` failure with a clear message (never a partial load); a missing `schemaVersion` →
  `MISSING_VERSION`; malformed JSON → `MALFORMED`; a depth-bomb / huge-node-count / cyclic-component /
  traversal-in-asset-path file → rejected with a specific message; the original file is never
  overwritten on a failed load (PF-7).
- **If it fails:** a bad file loads partially or crashes the editor → the load isn't all-or-nothing;
  it must reject with a message and leave the editor usable. The original file changes after a failed
  open → a write happened on a load path; it must not.
- **[Manual] spot-check:** hand-edit a copy of a `.vforge` to `"schemaVersion": 999`, Open it → clear
  error dialog, editor stays usable, file untouched.

> ✅ **Resolved (was Gap G-2, issue #142):** opening an older-schema `.vforge` now flags the first save
> to back up the original — `ProjectStore.load` reports the pre-migration version, `EditorState` carries
> a `backupOnNextSave` flag, and `DocumentController` passes `backup=true` on that save, writing a
> `<name>.bak` of the untouched original before the migrated form replaces it. FEATURES D9 is now met.
> **[Auto]:** `MigrationTest` (version reporting + end-to-end `.bak`), `DocumentSessionTest`
> (`backupOnNextSave` lifecycle). **[Manual] spot-check:** open the committed schema-1 `Demo.vforge`,
> Save over it, confirm a `Demo.vforge.bak` holding the original appears.

---

## 2. Canvas & rendering (FEATURES §1)

### CV-1 (C1) — Live IR render  [Manual]
- **Steps:** add a Column; inside it add a Row, a Text, and a Button.
- **Pass:** they render on the canvas with real Compose — the same widgets you'd get in the exported
  app, no placeholder art.
- **If it fails:** a node shows an error box instead of the widget → see RC-7 (per-node isolation);
  the rest of the canvas should still render.

### CV-2 (C2) — Click selects the deepest node  [Manual]
- **Steps:** add a Column, put a Text inside it. Click directly on the Text.
- **Pass:** the innermost Text is selected and outlined — not its parent Column.
- **If it fails:** the parent selects instead → the hit-test isn't descending to the deepest node.
  Nothing selects at all → the window may not hold focus yet (see #157 / SH-2).

### CV-3 (C3) — Hover highlight  [Manual]
- **Steps:** move the pointer over a node without clicking.
- **Pass:** a distinct hover outline appears and the layout does **not** shift.
- **If it fails:** the layout jumps on hover → the outline is taking layout space; it must draw as an
  overlay. A locked node shows no hover outline **by design** (#159) — it shouldn't read as clickable.

### CV-4 (C4) — Selection tracks bounds  [Manual]
- **Steps:** select a node, then resize the window or scroll a list that contains it.
- **Pass:** the selection outline stays on the node as it moves/resizes.
- **If it fails:** the outline lags or detaches → bounds aren't being recomputed; most visible after a
  scroll.

### CV-5 (C5) — Pan & zoom + correct hit-testing  [Manual]
- **Steps:** scroll to zoom; hold **Space** and drag to pan; try `Ctrl +`, `Ctrl −`, `Ctrl 0`. Then,
  at ~200% zoom, click a node.
- **Pass:** selection lands on the clicked node at **every** zoom/pan level — no offset between the
  pointer and what gets selected.
- **If it fails:** the selection lands off to one side at zoom → the content-space transform
  regressed; this is exactly the #116 fix (`editor/canvas/Selection.kt`). Space-drag selects instead
  of panning → the space-held pan mode isn't engaging.
- **Automated:** pure transform math — `CanvasTransformTest`, `HitTestTest`.

### CV-6 (C6) — Device frames, custom size, and fit  [Manual]
- **Steps:** open the toolbar device dropdown (◱), pick **Desktop 1920 × 1080**; then open it again
  and choose **Custom size…**, enter e.g. 1000 × 1400, OK. Press `Ctrl 9` (or View → Fit to Frame).
- **Pass:** the canvas reshapes to the chosen frame (centered, clipped); the custom size is accepted
  and shown as "Custom 1000 × 1400"; Fit zooms so the whole frame is visible. The choice persists per
  screen and is undoable.
- **If it fails:** a custom size snaps back to the default (#163) → the width/height was outside
  200–10000 or non-numeric; OK stays disabled until both are valid. A large frame stays clipped at
  100% → use `Ctrl 9` to fit (large frames don't auto-fit on every window resize by design, #59).
- **Automated:** registry/fit math — `DevicePreviewTest`, `CanvasViewportTest`.

### CV-7 (C7) — Drag to reparent/reorder on the canvas  [Manual]
- **Steps:** drag a node onto a different container; then try dragging a node into a non-container
  (e.g. a Text) and into its own child.
- **Pass:** a legal target shows a **green** insertion caret + target outline and the drop moves the
  node; an illegal drop (non-container, or into its own descendant) shows **red** and is rejected.
- **If it fails:** an illegal drop is accepted → the cycle/container guard broke. At zoom the caret is
  offset → the same content-space transform as CV-5.
- **Automated:** drop logic — `CanvasDropTest`.

### CV-8 (C8) — Per-node error isolation  [Manual]
- **Steps:** see RC-7.
- **Pass:** a failing node shows an error placeholder; siblings keep rendering.
- **If it fails:** see RC-7.

### CV-9 (C9) — Empty-container affordance  [Manual]
- **Steps:** add an empty Column and don't put anything in it.
- **Pass:** it still shows a minimum, selectable target (you can click and drop into it).
- **If it fails:** an empty container collapses to zero size and can't be selected → the min-size
  affordance is missing.
- **Automated:** model side — `ContainerNodesTest`.

### CV-10 (C10) — Multi-select and shared edit  [Manual]
- **Steps:** Shift-click or Ctrl-click several nodes; also drag a rubber-band marquee over empty
  canvas. With several **same-type** nodes selected, edit a shared prop in the inspector.
- **Pass:** all intended nodes are selected; the shared edit applies to every same-type node and an
  "N selected" banner shows.
- **If it fails:** the edit hits only one node → the shared-edit path only spans **same-type** nodes;
  a mixed-type selection edits just the primary. Marquee selects nothing → it only starts on empty
  canvas, not on a node.
- **Automated:** `SelectionModelTest`, `MarqueeTest`, `SharedEditTest`, `BatchOperationsTest`.

### CV-11 (C11) — Alignment guides  [Manual]
- **Steps:** View → Alignment guides. Select a node whose edge lines up with a sibling or parent.
- **Pass:** a guide line appears at the aligned edge/centre.
- **If it fails:** no guide on an obvious alignment → guides may be toggled off, or the edges aren't
  actually equal. (Free-move snapping is deferred, #129 — guides are display-only here.)
- **Automated:** `AlignmentGuidesTest`.

### CV-12 (C12) — Measure overlay  [Manual]
- **Steps:** select a node, hold **M** over the canvas.
- **Pass:** gaps to the parent's inner edges (and to siblings, #127) are shown in dp.
- **If it fails:** nothing shows on **M** → the tree/canvas must have focus; click the canvas first.
- **Automated:** `MeasureGapsTest`.

### CV-13 (C13) — Interactive preview  [Manual]
- **Steps:** View → Interactive preview. Click a Button, type in a TextField, drag a Slider.
- **Pass:** the real UI responds (ripples, editable inputs, moving slider); the selection overlay is
  gone; leaving preview restores the outlines and selection.
- **If it fails:** clicks still select instead of interacting → the overlay didn't turn off. Codegen
  must be unaffected — `FidelityTest` stays green.

---

## 3. Component palette (FEATURES §2)

### PL-1 (P1a) — Categorized from the registry  [Manual]
- **Steps:** open the palette (left).
- **Pass:** components are grouped by category (Layout, Content, Input, …), generated from the
  package — there is no hand-written UI per component.
- **If it fails:** a component is missing or uncategorized → the catalog/renderer/emitter parity is
  off.
- **Automated:** `CatalogConsistencyTest`.

### PL-2 (P2a) — Drag from palette to canvas  [Manual]
- **Steps:** drag a component from the palette onto a container on the canvas.
- **Pass:** it drops at the pointer's parent + index (a green caret shows where it will land).
- **If it fails:** nothing inserts → you released off a legal container; the caret only shows over one.
  A cycle-forming user component can't be dragged **by design** (greyed + tooltip, #70).

### PL-2b (P2a / #164) — Drop a palette component into the Layers tree  [Manual]
- **Steps:** start dragging a palette component, but drop it onto a **row in the Layers tree** instead
  of the canvas. Aim at the top / middle / bottom of a row.
- **Pass:** a drop line (before/after) or into-outline appears on the tree row, and releasing inserts
  the component there — same result as a canvas drop.
- **If it fails:** the tree ignores the drop → the pointer wasn't over a row; empty space below the
  last row does nothing. Both the tree and canvas try to claim it → they're disjoint surfaces and the
  tree wins only when the pointer is actually over a row (state precedence, #164).
- **Automated:** state precedence/fallback — `EditorStateTest` (#164 cases).

### PL-3 (P3a) — Search / filter  [Manual]
- **Steps:** type in the palette search box.
- **Pass:** the list filters by name/category as you type (type-ahead).
- **If it fails:** clearing the search doesn't restore the full list → the query didn't reset.

### PL-4 (P4a) — Insert via selection  [Manual]
- **Steps:** select a container on the canvas/tree, then single-click a palette row.
- **Pass:** the component inserts into the selected container.
- **If it fails:** it inserts at the root instead → nothing (or a non-container) was selected.

### PL-5 (P5a) — Favorites / recent  [Manual]
- **Steps:** clear the search so the ★ Favorites + Recent sections show; star a row to pin it; restart
  the app.
- **Pass:** Favorites persist across sessions; Recents are session-only (empty after restart).
- **If it fails:** favorites vanish on restart → the favorites pref didn't persist. Recents survive a
  restart → they shouldn't; recents are deliberately session-only.
- **Automated:** `FavoriteComponentsTest`, `PaletteFavoritesTest`.

### PL-6 (P6a) — User components in the palette  [Manual]
- **Steps:** select a subtree, Edit → Extract to Component.
- **Pass:** the new component appears under a "Components" category and can be inserted like a
  built-in.
- **If it fails:** the extracted component doesn't appear → the extraction didn't complete; check the
  tree for the new instance.
- **Automated:** `EditorComponentsTest`.

### PL-7 — Image component usability  [Manual]
- **Steps:** add an `Image` to a **new** project; open the inspector's source dropdown.
- **Pass (with the known limitation):** in the bundled **Gallery sample** the dropdown lists the
  sample's classpath assets and the image renders.
- **If it fails (expected in a fresh project):** ⚠️ **Known gap G-1 (issue #141)** — the dropdown only
  lists **already-imported** assets and there is **no disk-import UI**, so a fresh project has no image
  to choose. This is a documented limitation, not a regression; ship-with-caveat or implement import
  (AS-1…AS-5).

---

## 4. Tree / layers panel (FEATURES §3)

### TR-1 (T1) — Hierarchical tree + bidirectional selection  [Manual]
- **Steps:** confirm the tree mirrors the canvas structure; expand/collapse a container; select a node
  in the tree, then select a different one on the canvas.
- **Pass:** selecting in the tree highlights on the canvas and vice-versa; expand/collapse works.
- **If it fails:** selection is out of sync between tree and canvas → they should share one selection
  model. The tree is built from the IR, not the semantics tree (ADR-009).

### TR-2 (T2) — Drag reparent/reorder within the tree  [Manual]
- **Steps:** drag a tree row onto another row (top = before, middle of a container = into, bottom =
  after). Try an illegal target (into a leaf, into its own child).
- **Pass:** a before/into/after indicator shows and the move applies; illegal drops are rejected —
  the same rules as the canvas.
- **If it fails:** a cycle or non-container drop is accepted → the tree must share the canvas drop
  validation.
- **Automated:** `CanvasDropTest` (shared logic).

### TR-3 (T3) — Rename  [Manual]
- **Steps:** double-click a row (or select it and press **F2**), type a new name, Enter.
- **Pass:** `Node.name` updates and shows in the tree; the generated code structure is unaffected
  (name is display only).
- **If it fails:** a locked node won't rename **by design** (#159). Double-clicking a **component
  instance** enters the component instead of renaming (#68) — that's expected.

### TR-4 (T4) — Lock / hide  [Manual]
- **Steps:** select a node, toggle **L** (lock); then toggle hide. Try to select/drag/drop-into/rename
  the locked node.
- **Pass:** a **locked** node shows a padlock badge on the canvas + a tree marker and can't be
  selected, dragged, dropped-into, or renamed (per-node — a container nested inside it still accepts
  drops); render/codegen unaffected. A **hidden** node is removed from render **and** codegen.
- **If it fails:** the lock "looks inert" → confirm the padlock badge/amber outline appears (that was
  the #159 fix; without the indicator it merely *looked* inert while already blocking selection). A
  drop still lands on a locked container → the drop guard regressed.
- **Automated:** `canDrop`/`renameNode`/`canvasDropTarget`/`lockedNodes` logic; hidden-drops-from-
  codegen via goldens.

### TR-5 (T5) — Keyboard navigation  [Manual]
- **Steps:** click a tree row to focus the tree, then use ↑/↓, ←/→, Enter, Del.
- **Pass:** ↑/↓ move selection (skipping locked rows), ←/→ collapse/expand or step in/out, Enter
  renames, Del removes.
- **If it fails:** arrows do nothing → the tree doesn't have focus; click a row first.
- **Automated:** decisions — `TreeKeyNavTest`.

### TR-6 (T6) — Search within the tree  [Manual]
- **Steps:** type in the Layers search box.
- **Pass:** the tree filters to matching nodes, keeping their ancestor path visible; a no-match
  message shows for a query that matches nothing.
- **If it fails:** a match under a collapsed parent doesn't show → the filter must force-expand kept
  paths.
- **Automated:** `TreeSearchTest`.

### TR-7 (#160) — Right-click context menu  [Manual]
- **Steps:** right-click a node in the tree, and again on the canvas.
- **Pass:** the node is selected and a menu opens (Cut / Copy / Paste / Duplicate / Delete / Rename /
  Extract to Component / Enter Component) with correct enablement; a **locked** node opens no menu.
- **If it fails:** the menu opens at the wrong spot → positioning uses the per-row/hit window
  coordinates; a right-click inside a multi-selection keeps the selection (doesn't collapse to one).
- **Automated:** enablement — `ContextMenuTest`.

---

## 5. Property inspector (FEATURES §4)

### IN-1 (I1/I2) — Data-driven typed controls  [Manual]
- **Steps:** select nodes of different types (Text, Button, Image, a container).
- **Pass:** the inspector shows type-appropriate controls generated from `PropDefinition` — color
  picker, Dp stepper, enum dropdown, bool switch, text field, shape — with **no per-component UI**.
- **If it fails:** a new component needs hand-written inspector code → that's an anti-pattern here; the
  inspector must be driven from `PropDefinition`.
- **Automated:** `PropEditingTest`, `NodeDisplayTest`.

### IN-2 (I3) — Ordered modifier editor  [Manual]
- **Steps:** add two modifiers (e.g. `padding` then `background`); drag to reorder them; toggle one
  off.
- **Pass:** the order visibly changes the render (padding-before vs -after background looks different)
  and matches the generated modifier chain.
- **If it fails:** reordering has no visual effect → modifiers may be stored unordered somewhere;
  order is **semantic** and must never be a map.
- **Automated:** order semantics — `ModifierOrderTest` + golden.

### IN-3 (I4/H3) — Theme token picker  [Manual]
- **Steps:** on a color (or typography/shape) prop, switch from a literal to a **theme token**.
- **Pass:** the prop shows the token name and the canvas uses the theme's value.
- **If it fails:** the canvas keeps the literal → the binding didn't take; check the prop shows the
  token, not a hex value.
- **Automated:** `ThemeCodegenTest`.

### IN-4 (I5) — Live update  [Manual]
- **Steps:** edit any prop.
- **Pass:** the canvas updates within a frame — there is no Apply button.
- **If it fails:** you need to click away for the change to show → a commit is being deferred.

### IN-5 (I6) — Raw expression escape hatch  [Manual]
- **Steps:** set a prop to a **raw expression**.
- **Pass:** the node is flagged **unverified**, the canvas shows a placeholder, and the expression is
  **never evaluated** — it's emitted as text in codegen only.
- **If it fails:** the expression produces a real value on the canvas → **security bug**: raw
  expressions must never be executed (PF-4 / GC-4).

### IN-6 (I7) — Reset to default  [Manual]
- **Steps:** edit a prop, then use its per-prop revert.
- **Pass:** the prop returns to its default and the control reflects it.
- **If it fails:** revert clears to empty instead of the default → the default isn't being reapplied.

### IN-7 (I8) — Validation feedback  [Manual]
- **Steps:** enter an invalid value (a bad number, or an illegal screen name like `1 Screen`).
- **Pass:** an inline error shows **before** codegen; you can't proceed with the invalid value.
- **If it fails:** an illegal screen name is accepted → it would break the generated file name; screen
  names must be legal Kotlin identifiers, validated at edit time.
- **Automated:** `PreferencesValidationTest`, screen-name validation (`ScreenSessionTest`).

---

## 6. Document & history (FEATURES §5)

Covered largely by §1 (RC-1…RC-3, RC-6, RC-8). Additional area-specific items:

### DC-1 (D5) — Copy / paste / duplicate  [Manual]
- **Steps:** copy a node and paste it; duplicate another; copy a multi-node selection and paste.
- **Pass:** pasted/duplicated nodes get **fresh IDs** and target the current selection; a multi-node
  paste is a single undo entry.
- **If it fails:** a paste reuses IDs (two nodes react as one) → the fresh-id pass didn't run.
- **Automated:** `EditorStateTest`, `BatchOperationsTest`.

### DC-2 (D6) — Multiple screens  [Manual]
- **Steps:** add screens via the switcher; rename one; switch between them.
- **Pass:** each screen is independent and exports its own composable.
- **If it fails:** a duplicate/illegal screen name is accepted → names must be unique legal
  identifiers (collision → colliding `.kt`).
- **Automated:** `ScreenCommandTest`, `ScreenSessionTest`.

### DC-3 (D7) — Reusable components + edit-in-place  [Manual]
- **Steps:** extract a subtree to a component; double-click an instance to enter it; edit the
  definition; try to insert the component inside itself.
- **Pass:** editing the definition updates **every** instance; a cycle-forming insert is refused
  (greyed entry + tooltip).
- **If it fails:** editing an instance doesn't propagate → definitions are resolved at render/codegen,
  never inlined; a broken instance suggests inlining crept in.
- **Automated:** `ComponentGraphTest`, `CycleGuardTest`, `EditInPlaceTest`, render/codegen goldens.

### DC-4 (D7 / params) — Component parameters  [Manual]
- **Steps:** while editing a component, promote a prop to a parameter; select an instance and set its
  arg in the inspector's Parameters section.
- **Pass:** codegen emits a typed function parameter + call argument; the canvas resolves the arg per
  instance.
- **If it fails:** an instance ignores its arg → the ParamRef didn't bind; unset args fall back to the
  parameter default.
- **Automated:** `ParameterCommandTest`, `ParameterTypeTest`, `ParameterEditTest`,
  `ParameterBindingTest`, `ParameterTypesTest` + goldens.

### DC-5 (D8) — Recent projects  [Manual]
- **Steps:** open/save a couple of projects, then File → Open Recent.
- **Pass:** the list shows them most-recent-first; a stale (moved/deleted) path reports and is dropped.
- **If it fails:** a deleted path opens to an error with no cleanup → stale entries should be pruned.
- **Automated:** `RecentProjectsTest`, `RecentProjectsStateTest`.

### DC-6 (D9) — Migration on load + backup  [Manual]
- **Steps:** open the committed schema-1 `Demo.vforge`, then Save over it.
- **Pass:** it migrates to the current schema and loads; the save writes a `Demo.vforge.bak` of the
  original first.
- **If it fails:** no `.bak` after saving a migrated file → the backup-on-migrate flag didn't carry
  (this was gap G-2, fixed in #142).
- **Automated:** `MigrationTest` (migrates the real committed fixture + asserts backup-on-migrate),
  `DocumentSessionTest` (flag lifecycle).

---

## 7. Theming (FEATURES §6)

### TH-1 (H1) — Theme editor  [Manual]
- **Steps:** View → Theme…; edit colors / typography / shapes / spacing.
- **Pass:** the canvas updates live and each edit is undoable; the dialog is sized to its content (no
  big empty area below the controls, #162).
- **If it fails:** a tall white gap under the dialog → the #162 fix regressed (window must pack to
  content).
- **Automated:** `ThemeCommandTest`, `ThemeEditTest`.

### TH-2 (H2) — Light/dark preview  [Manual]
- **Steps:** View → Dark canvas toggle.
- **Pass:** the canvas swaps between light and dark; both value sets are stored.
- **If it fails:** text goes faint/invisible on the canvas → the content-color default regressed
  (#155); canvas text defaults to opaque black to match codegen.

### TH-3 (H4) — Theme codegen  [Manual]
- **Steps:** Export the project and open the generated `Theme.kt`.
- **Pass:** `AppTheme(darkTheme, content)` is generated and `Main` wraps the screen in it.
- **If it fails:** see RC-5; the theme golden/compile gate covers this.
- **Automated:** `ThemeCodegenTest`, `CompilationTest` (theme case).

### TH-4 (H5) — Token rename propagation  [Manual]
- **Steps:** rename a theme token.
- **Pass:** every reference across all screens updates in **one** undoable step.
- **If it fails:** some references keep the old token → the rename didn't span all `ThemeRef`s.
- **Automated:** command-level.

---

## 8. Codegen / export (FEATURES §7)

### EX-1 (G1/G2/G7) — Generate + compile + format  [Auto]
- **Pass:** see RC-5 — goldens, compile gate, and formatting all green.
- **If it fails:** see RC-5.

### EX-2 (G3) — Live code preview  [Manual]
- **Steps:** View → Code preview; select a node; then click somewhere in the code text.
- **Pass:** the panel shows the active screen's Kotlin and updates on edit; selecting a node scrolls
  to and highlights its code; clicking code selects the matching node. When a component is open for
  edit-in-place, the panel shows **that component's** code (#69).
- **If it fails:** the highlight targets the wrong lines → the node↔span map is stale; it must
  regenerate with the document.
- **Automated:** span mapping — `SourceSpansTest`, `CodeSpanLookupTest`, `CodePreviewContentTest`.

### EX-3 (G4) — Export loose `.kt`  [Manual]
- **Steps:** File → Export → .kt files; choose a directory. Repeat into the same directory.
- **Pass:** files are written atomically; if files already exist, an overwrite confirmation lists
  them.
- **If it fails:** an overwrite happens with no prompt → the confirmation (FW-5) is bypassed.
- **Automated:** `ProjectExporterTest`, `DesktopExporterTest`.

### EX-4 (G5) — Export a runnable Gradle project  [Manual]
- **Steps:** File → Export → Gradle project; in a shell, run `./gradlew run` in the output directory.
- **Pass:** the exported project compiles and runs unmodified, showing the screen.
- **If it fails:** `gradlew run` fails to compile → a codegen/scaffold mismatch; the golden compile
  gate should have caught the code, so suspect the scaffold (build files, versions).
- **Automated:** scaffold — `ExportSampleTest`.

### EX-5 (G6) — Generated header  [Auto]
- **Pass:** every exported `.kt` header names the source `.vforge`, the schema version, and warns
  against hand-edits.
- **If it fails:** covered by goldens.

### EX-6 (G8) — Copy composable to clipboard  [Manual]
- **Steps:** in the Code preview panel, use Copy, then paste elsewhere.
- **Pass:** the active screen's composable is on the clipboard.
- **If it fails:** nothing pastes → the clipboard write didn't happen (GUI-only path).

### EX-7 (G10) — Safe regeneration  [Manual]
- **Steps:** File → Regenerate Gradle project into a previously-exported directory; then add your own
  hand-written file there and regenerate again.
- **Pass:** ViewForge replaces its own files and removes its own orphans, but **refuses** to overwrite
  files it doesn't own (and reports them).
- **If it fails:** a hand-written file is clobbered → the ownership check regressed; ViewForge must
  never overwrite unowned files.
- **Automated:** `RegenerationPlanTest`, `RegenerationTest`.

---

## 9. Editor shell / menus / shortcuts (FEATURES §8)

### SH-1 (S1) — Resizable + persisted panels  [Manual]
- **Steps:** drag the panel dividers to resize; toggle panels via the View menu; restart the app.
- **Pass:** panel widths and visibility persist across sessions.
- **If it fails:** widths reset on restart → the layout pref didn't save (drag-end persists, not every
  pixel).
- **Automated:** `PanelLayoutTest`, `PanelVisibilityTest`, `PreferencesStoreTest`.

### SH-2 (S2) — Keyboard shortcuts  [Manual]
- **Steps:** exercise `Ctrl+N/O/S/Shift+S`, `Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y`, `Ctrl+X/C/V/D`,
  `Del`/`Backspace`, `F2`, `Ctrl +/−/0`, `Ctrl 9`, `Ctrl+Shift+P`, hold **Space** (pan), hold **M**
  (measure). Try undo **immediately after launch** without clicking anything first, and again while a
  rename/search field is focused.
- **Pass:** each fires the documented action; shortcuts show in menu labels; undo works right after
  launch; typing in a rename/search field is **not** hijacked (in-field Ctrl+C/V/A still edit text).
- **If it fails:** shortcuts are dead until you click a node → window-focus timing (#157). Ctrl+Z does
  nothing while a text field is focused → the capture-phase undo/redo (#166) regressed. `Ctrl+Shift+P`
  crashes → the palette focus-in-popup fix (#168) regressed.
- **Automated:** menu enablement — `AppMenuBarTest`.

### SH-3 (S3) — Editor light/dark chrome  [Manual]
- **Steps:** View → Dark editor.
- **Pass:** the editor chrome flips light/dark, independent of the project's canvas theme, and
  persists across restart.
- **If it fails:** the canvas theme flips too → the two themes must stay separate (S3).
- **Automated:** `PreferencesStoreTest` (chrome round-trip), `EditorStateTest`.

### SH-4 (S4) — Command palette  [Manual]
- **Steps:** `Ctrl+Shift+P`; type to fuzzy-match a command (File/Edit/View + Go-to-screen +
  Add-component); navigate with ↑/↓/Enter/Esc.
- **Pass:** the launcher ranks matches; disabled commands are greyed; Enter runs, Esc closes.
- **If it fails:** the palette opens but can't type / crashes → the in-popup focus fix (#168).
- **Automated:** ranking — `CommandPaletteTest`.

### SH-5 (S5) — Preferences dialog  [Manual]
- **Steps:** File → Preferences…; edit the autosave interval, undo-history depth, default export path;
  enter an invalid number.
- **Pass:** invalid input blocks Save inline (I8); valid values persist and take effect live (the
  autosave timer re-keys, history trims).
- **If it fails:** an out-of-range interval is accepted → it should clamp to [2, 600]s.
- **Automated:** `PreferencesValidationTest`, `HistoryTest`, `PreferencesStoreTest`.

### SH-6 (S6) — Local crash reporter  [Manual]
- **Steps:** (see also RC-3) force an uncaught-exception path if feasible.
- **Pass:** a crash log is written locally under the config dir; **no network transmission**; the log
  carries only a stacktrace + thread name, **no project content** (PR-2/PR-3).
- **If it fails:** a crash log contains project data → privacy bug; logs must never include document
  content.
- **Automated:** `CrashReporterTest`.

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

Run in `:app:run`. Each step lists what a pass looks like and the most likely failure. If any step
fails, **no-go**.

1. **Create** — launch to a blank project; add a Column, then a Text and a Button inside it (via
   palette click **and** drag-from-palette). *Pass:* both insert where dropped and render. *If it
   fails:* a drag that inserts nothing means you released off a legal container.
2. **Edit** — change the Text's `text`, `fontSize`, `color` (bind color to a theme token); add a
   `padding` then a `background` modifier and **reorder** them. *Pass:* canvas updates live; the
   reorder visibly changes the render. *If it fails:* no visual change on reorder → modifier order
   isn't taking effect.
3. **Theme** — View → Theme…, change `primary`; toggle **Dark canvas**. *Pass:* live update; the
   token-bound Text tracks the change; dark canvas keeps text readable (#155). *If it fails:* faint
   text on dark → content-color default regressed.
4. **Multi-select** — Shift-click two same-type nodes, edit a shared prop. *Pass:* applies to both
   with an "N selected" banner. *If it fails:* only one changes → the selection wasn't same-type.
5. **Save** — File → Save As… to `smoke.vforge`. *Pass:* the title clears the unsaved •.
6. **Reopen** — File → New, then File → Open `smoke.vforge`. *Pass:* identical to what was saved. *If
   it fails:* a missing prop points at file-dialog wiring, not the model (RC-1).
7. **Undo/redo** — undo the last several edits to an earlier state, then redo. *Pass:* returns
   exactly; a drag/scrub was a single entry. *If it fails:* undo of a text-field edit is dead → #166.
8. **Export + compile** — File → Export → Gradle project; in a shell run `./gradlew run` in the
   output. *Pass:* it compiles and runs, showing the screen. (Or export loose `.kt` and compile.)
9. **Device profile + zoom** — switch the device dropdown to 1920×1080 (and try Custom size…); zoom to
   ~200% and pan with Space-drag; click a node. *Pass:* the frame reshapes; the selection outline
   lands exactly on the clicked node at zoom (no offset). *If it fails:* offset at zoom → #116.
10. **Code preview** — View → Code preview; select a node. *Pass:* the panel highlights + scrolls to
    that node's code; clicking code selects the node.
11. **Corrupt/newer file** — Open a copy of a `.vforge` hand-edited to `"schemaVersion": 999`. *Pass:*
    a clear error dialog; the editor stays usable; the file is untouched.
12. **Close-when-dirty** — make an edit and close the window. *Pass:* Save/Discard/Cancel prompt
    behaves correctly (Cancel keeps you in the editor; a failed/cancelled Save keeps the app open).

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

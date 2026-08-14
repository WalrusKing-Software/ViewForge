# ViewForge — Features

Priority key: **P0** = required for Phase 1 exit · **P1** = strongly wanted · **P2** = later ·
**P3** = someday/maybe.

Features are written as user-visible capabilities with acceptance criteria, so they can be turned
into issues directly.

---

## 1. Canvas & rendering

| # | Feature | Pri | Acceptance |
|---|---------|-----|------------|
| C1 | Live IR rendering with real Compose | P0 | Any valid IR tree renders; no visual difference vs. compiled output. |
| C2 | Click-to-select deepest node | P0 | Clicking selects the innermost node containing the point; overlay outlines it. |
| C3 | Hover highlight | P0 | Hovering shows a distinct, non-layout-affecting outline. |
| C4 | Selection overlay with bounds | P0 | Outline tracks node bounds through scroll/resize/recomposition. |
| C5 | Pan & zoom | P0 | Scroll/pinch to zoom, space-drag to pan; hit-testing correct at all zoom levels. |
| C6 | Device preview frames | P0 | Selectable viewport profiles (desktop sizes in Phase 1); canvas clips to the frame. (#58: `DeviceProfiles` registry (editor/state, dp as `Float`) + a toolbar dropdown; selection is the undoable `SetPreviewProfile` command persisted on `Screen.previewProfile`; the canvas frames the root to the profile size (centered, clipped) under the C5 zoom/pan layer, defaulting when unset. ADR-026. #59: auto-fit-to-viewport — `CanvasViewport.fittedTo` sets the clamped fit zoom (centered, view-state only), applied on profile change (canvas `LaunchedEffect`) and on demand via View → Fit to Frame / Ctrl+9.) |
| C7 | Drag to reparent/reorder | P0 | Dragging a node shows a drop indicator; illegal drops (into a non-container, into own descendant) are rejected visually. |
| C8 | Per-node error isolation | P0 | A node that throws during render shows an error placeholder; the rest of the canvas keeps working. |
| C9 | Empty-container affordance | P0 | Zero-size containers render a minimum editor-only target so they remain selectable. |
| C10 | Multi-select | P1 | Shift/ctrl-click; shared property edits apply to all selected. (#92 epic, shipped in 4 slices: ordered `EditorState.selectedIds` (last = primary) + `selectionAnchor`; canvas/tree ctrl-toggle and tree shift-range via `LocalWindowInfo.keyboardModifiers`; batch delete/duplicate + multi-node `List<Node>` clipboard as one `CompositeCommand`; and inspector `setPropShared` fanning a prop edit out to every **same-type** selected node (coalesced like single-node) with an "N selected" banner. Modifier edits and hide/lock stay primary/per-row. Canvas marquee (#93) shipped: a press-drag on the frame/empty canvas rubber-band selects every fully-enclosed node via the pure `marqueeSelection` + `EditorState.setSelection`, arbitrated against space-pan (C5) and drag-to-reparent (C7) by the press location.) |
| C11 | Alignment guides / snapping | P2 | Guides appear when edges align during drag. |
| C12 | Measure/spacing overlay | P2 | Hold a modifier key to show spacing between selection and neighbors. |
| C13 | Interactive preview mode | P2 | Toggle to interact with the real UI (buttons clickable) rather than edit it. |

## 2. Component palette

| # | Feature | Pri | Acceptance |
|---|---------|-----|------------|
| P1a | Categorized palette from registry | P0 | Palette is generated from the package's component definitions; adding a component requires no palette code. |
| P2a | Drag from palette to canvas | P0 | Drop position determines parent and index. |
| P3a | Search/filter | P0 | Type-ahead filtering by name and category. |
| P4a | Insert into tree panel | P1 | Can add via the tree as well as the canvas — needed when targets are tiny. |
| P5a | Favorites / recent | P2 | — |
| P6a | Custom user components in palette | P1 | User-defined components appear alongside built-ins. (#18: `EditorState.palette` appends the document's components as instance entries beneath the built-ins, updating as they are extracted; click/drag inserts an instance. ADR-024.) |

### Phase 1 component set (deliberately small)

**Layout:** `Column` · `Row` · `Box` · `Spacer` · `Surface` · `Card` · `Scaffold` (slots) ·
`LazyColumn` (static children only) · `LazyRow` (static children only)

**Content:** `Text` · `Image` · `Icon` · `Divider` / `HorizontalDivider`

**Input:** `Button` · `OutlinedButton` · `TextButton` · `IconButton` · `TextField` ·
`OutlinedTextField` · `Checkbox` · `Switch` · `RadioButton` · `Slider`

**Navigation (structural only):** `TopAppBar` · `BottomAppBar` · `NavigationBar` (Phase 2)

> Resist expanding this list before M9. Breadth is easy and shallow; the exit criteria demand depth.

> **Shipped for Phase 1 (M9):** `Column` · `Row` · `Box` · `Spacer` · `LazyColumn` · `LazyRow`
> (static children only) · `Text` · `Button` · `Image` — the set exit criterion #1 requires (nested
> layout, text, buttons, images, a scrollable list), each with a renderer + emitter + golden triple in
> lockstep. `Image` sources are `ResourceRef`s picked from the project's assets; disk import and
> copying assets into an exported project are the tracked follow-up (ADR-021). The remaining
> Content/Input/Navigation entries above join the set one triple at a time.

> **Post-Phase-1 catalog expansion (issue #16):** added as renderer + emitter + golden triples with an
> in-process compile check; no pipeline or inspector change.
> - *Slice 1:* `Card` · `Surface` · `HorizontalDivider` (`thickness`) · `Checkbox` · `Switch`
>   (`checked`/`enabled` + a raw `onCheckedChange`).
> - *Slice 2:* `OutlinedButton` · `TextButton` (Button's `onClick` + content shape) · `Slider`
>   (`value`/`enabled` + a raw `onValueChange`) · `CircularProgressIndicator` · `LinearProgressIndicator`
>   (indeterminate).
> - *Slice 3:* `Icon` — a curated Material-icon `Enum` (`icon`) emitted as `Icons.Filled.<name>` from
>   `material-icons-core` (already on the classpath; no new dependency). The allowlist lives once in
>   `render/Values.kt` (`ICON_NAMES`).
> - *Slice 4:* `TextField` · `OutlinedTextField` — `value` (String) + a raw `onValueChange`
>   (no-op on the canvas, PF-4; edit `value` in the inspector). `label`/`placeholder` (composable slots)
>   deferred.
> - *Slice 5:* `TopAppBar` (a named `title` slot; **experimental Material3**, so the generated screen is
>   annotated `@OptIn(ExperimentalMaterial3Api::class)`) · `BottomAppBar` (RowScope content). Introduces
>   named-slot → named-lambda-arg emission and the opt-in plumbing.
> - *Slice 6:* `Scaffold` — `topBar`/`bottomBar` named slots + a `content` lambda receiving
>   `innerPadding: PaddingValues`. Content is wrapped in `Column(Modifier.padding(innerPadding))` in both
>   the renderer and codegen (consuming the inset — lint-clean — while keeping canvas/output identical).
>
> **Issue #16 is complete**: every component in the Phase-1 set above (Layout, Content, Input, and the
> structural Navigation bars) now has a renderer + emitter + golden triple. `NavigationBar` remains a
> Phase-2 item.

## 3. Tree / layers panel

| # | Feature | Pri | Acceptance |
|---|---------|-----|------------|
| T1 | Hierarchical tree of the document | P0 | Reflects IR exactly; expands/collapses; syncs bidirectionally with canvas selection. |
| T2 | Drag to reparent/reorder | P0 | Same validation rules as the canvas. |
| T3 | Rename nodes | P0 | Sets `Node.name`; shown in tree; does not affect codegen structure. |
| T4 | Lock / hide toggles | P1 | `locked` prevents selection; `hidden` removes from render **and** codegen. |
| T5 | Keyboard navigation | P1 | Arrow keys traverse, Enter renames, Delete removes. |
| T6 | Search within tree | P2 | — |

## 4. Property inspector

| # | Feature | Pri | Acceptance |
|---|---------|-----|------------|
| I1 | Data-driven prop editors | P0 | Controls generated from `PropDefinition`; **no per-component inspector code**. |
| I2 | Typed controls | P0 | Color picker, Dp stepper, enum dropdown, bool switch, text field, per `PropType`. |
| I3 | Ordered modifier list editor | P0 | Add, remove, **drag-reorder**, toggle-enable. Order visibly matters. |
| I4 | Theme token picker | P0 | Any themeable prop can bind to a token instead of a literal. |
| I5 | Live update | P0 | Canvas reflects edits within one frame; no apply button. |
| I6 | Raw expression escape hatch | P1 | Advanced-only; node flagged "unverified"; canvas shows a placeholder. |
| I7 | Reset to default | P1 | Per-prop revert. |
| I8 | Validation feedback | P1 | Invalid values are shown inline before codegen ever runs. |
| I9 | Responsive overrides | P2 | Per-breakpoint prop values (needed for Phase 2). |

> **Per-component prop coverage (issue #17):** fleshing out each component's editable props toward its
> full idiomatic set — pure catalog schema + renderer/emitter/golden work, no inspector code (I1). New
> value emitters (`sp`, `int`) and text enums (`FontWeight`/`TextAlign`/`TextOverflow`) land here.
> - *Slice 1 — Text:* `fontSize` (sp) · `fontWeight` · `textAlign` · `maxLines` · `overflow`.

## 5. Document & history

| # | Feature | Pri | Acceptance |
|---|---------|-----|------------|
| D1 | New / open / save / save-as | P0 | `.vforge` round-trips losslessly. (#56: closing the window with unsaved edits now prompts **Save / Discard / Cancel** — `:app` `onCloseRequest` raises a flag when `isDirty`, and the shell's `ExitConfirmation` reuses the `DocumentController` save flow; Save exits only if the save actually landed, a cancelled Save As aborts the close. Recovery (#54) remains the safety net for a hard kill.) |
| D2 | Undo / redo | P0 | Correct across ≥50 mixed operations; verified by property-based test. |
| D3 | Gesture coalescing | P0 | A drag or slider scrub is one history entry, not hundreds. |
| D4 | Autosave + crash recovery | P0 | Timer-based sidecar; on next launch, offer to restore. (#54: `RecoveryStore`/`RecoverySnapshot` in `core/project` write an atomic `recovery.json` to `ConfigDir` while the document is dirty; a shell `RecoveryController` ticks on a fixed interval and clears it only on a clean state, so a crash **or** quit-without-save leaves it to be restored. Load is total (corrupt/missing → no recovery, never blocks launch). ADR-025. Interval config is S5 (#55, shipped); save-prompt-on-close is #56, shipped — see D1.) |
| D5 | Copy / paste / duplicate | P0 | Fresh IDs generated; paste targets current selection. |
| D6 | Multiple screens per project | P0 | Screen switcher; each exports its own composable. |
| D7 | Reusable user components | P1 | Extract selection → component; instance references update on edit. Cycle detection enforced. (#18: `extractComponent` command + Edit → Extract to Component; instances are references resolved at render/codegen, so a definition edit reaches every instance; cycles guarded on load and at render. Parameters deferred. ADR-024.) (#61: **editing a component in place is now a live gesture** — double-click a palette component to open it, edit its tree with the canvas/tree/inspector, "Back to <Screen>" to return. Root-agnostic commands + an active edit surface; ADR-027.) (#70: cycle-forming inserts are **refused at edit time** too — with a component open, a palette entry that would (transitively) contain it is greyed out with a tooltip and paste is disabled; `Project.insertionWouldCycle` in `core/model`, shared with the load-time validator. The render/load guards remain as defence in depth.) |
| D8 | Recent projects list | P1 | File → **Open Recent** submenu of recently opened/saved projects. (#88: a `recentProjects` list persisted in `core/prefs` `EditorPreferences` — absolute paths, most-recent-first, de-duplicated and capped via `RecentProjects`; forward-tolerant so `prefsVersion` is unchanged. `EditorState` holds the live list, applied from prefs before the first frame and updated on a successful open/save; a recent that no longer opens reports the error and is dropped. The shell `PreferencesController` now **load-merge-saves** so persisting one facet never clobbers another — fixing a latent bug where a panel toggle reset the #55 autosave interval.) |
| D9 | Schema migration on load | P0 | Older files migrate with a backup written first. |

## 6. Theming

| # | Feature | Pri | Acceptance |
|---|---------|-----|------------|
| H1 | Theme editor (colors/typography/shapes/spacing) | P0 | Edits apply live across the canvas. (M8: modal `ThemeEditor` dialog; every edit is a command, so it is undoable and the canvas — themed from the project via `projectColorScheme`/`projectShapes` — updates live. ADR-020.) |
| H2 | Light/dark pairs | P0 | Toggle canvas between modes; both values stored. (M8: toolbar light/dark toggle drives `EditorState.canvasDark`; both halves of each `ColorPair` are always stored.) |
| H3 | Token references from props | P0 | See I4. (Shipped M5: color token picker + typography picker.) |
| H4 | Theme codegen | P0 | Emits a usable `MaterialTheme` wrapper. (M8: `ThemeEmitter` generates `Theme.kt` with `AppTheme(darkTheme, content)`; the Gradle scaffold's `Main` wraps the screen in it. ADR-020.) |
| H5 | Token rename propagation | P1 | Renaming a token updates all references. (M8: `RenameThemeToken` rewrites the theme key **and** every `ThemeRef` across all screens in one undoable command.) |
| H6 | Import theme from existing Kotlin | P3 | Requires parsing — deliberately deferred. |

## 7. Code generation & export

| # | Feature | Pri | Acceptance |
|---|---------|-----|------------|
| G1 | Generate Kotlin for all supported nodes | P0 | Golden test per component and modifier. |
| G2 | Generated output compiles | P0 | **CI compiles the generated fixtures.** Not just string comparison. |
| G3 | Live code preview panel | P0 | Side-by-side view updating with selection; read-only in v1. (#50: a read-only, selectable monospace panel showing the active screen's generated Kotlin, refreshing as the document changes. Reaches the generator through the Compose-free `CodePreviewService` seam (ADR-013), bound in `:app` to `generateScreen`; toggled from the View menu, resizable. Generation failures render a visible in-panel error.) (#51: **selecting a node scrolls the panel to, and highlights, the code emitted for it** — the seam's `generate*WithSpans` returns a node→source-range map (`GeneratedSource`/`PreviewSource`) alongside the source, built by an instrumented emitter pass that brackets each node with marker comment lines and strips them back to byte-identical output (invariant-tested over every golden); the panel reads `selectedId` against the map to draw a background highlight + auto-scroll. Works for nodes in slots and lazy lists; click-code-to-select is a further follow-up.) (#52: the panel's **visibility and width now persist** across sessions via `core/prefs` `PanelLayout` — a `codePreviewVisible` flag + `codePreviewWidth`, defaulting to hidden and wider; loaded before first frame in `:app` and saved on toggle/resize through the shell `PreferencesController`, exactly like the #43 side panels.) |
| G4 | Export loose `.kt` files | P0 | To a user-chosen directory, with overwrite confirmation. |
| G5 | Export runnable Gradle project | P0 | Scaffolds a Compose Desktop project that runs with `./gradlew run` unmodified. |
| G6 | Generated-file header | P0 | Names source file, schema version, and warns about hand-edits. |
| G7 | Formatting pass on output | P0 | A formatting pass makes output idiomatic, not merely valid (M7: a deterministic normalizer strips KotlinPoet's redundant `public`; KotlinPoet handles imports/indent/wrap. ADR-019). |
| G8 | Copy composable to clipboard | P1 | For pasting into an existing project. |
| G9 | Per-target source-set routing | P1 | Phase 2+: files land in `commonMain` vs `androidMain` correctly. |
| G10 | Regeneration into an owned directory | P1 | Wholesale regeneration is safe and doesn't clobber user files. (#107: **shipped** — File → Regenerate Gradle project writes an `.viewforge/manifest.json` of owned paths and, on re-export, replaces ViewForge's own files, deletes its orphans, and **refuses** to overwrite unowned files (reported, not clobbered). Ownership = manifest, with the G6 header as a fallback signal for text files; manifest load is total (corrupt → nothing owned). Pure `planRegeneration` + `ProjectExporter.regenerate`/`regenerationPlan`, root-confined `GuardedWriter.delete`. ADR-029. No `.vforge` schema change.) |

## 8. Editor shell

| # | Feature | Pri | Acceptance |
|---|---------|-----|------------|
| S1 | Dockable/resizable panels | P0 | Layout persists across sessions. |
| S2 | Keyboard shortcuts | P0 | Standard set; discoverable in menus. |
| S3 | Editor light/dark theme | P1 | Independent of the *project's* theme — don't conflate them. (#104: **shipped** — a View → Dark editor toggle switches the chrome `MaterialTheme` between `darkColorScheme()`/`lightColorScheme()` via `EditorState.chromeDark`, wholly independent of `canvasDark` (H2). Persisted additively as `EditorPreferences.chromeDark` (defaults dark, so upgrading users see no change; `prefsVersion` unchanged), seeded before the first frame.) |
| S4 | Command palette | P2 | — |
| S5 | Preferences | P1 | Autosave interval, history depth, default export path. (#55: **autosave interval** persisted as `EditorPreferences.autosaveIntervalSeconds`, clamped on load. #105: **shipped** — a File → Preferences… dialog edits autosave interval, undo `historyDepth`, and `defaultExportPath` (two new additive prefs; `prefsVersion` unchanged), rejecting invalid numeric input inline (I8) so a bad value is never written. Edits persist through the load-merge `PreferencesController` and take effect live — the autosave timer re-keys on the interval and a lowered depth trims `History` immediately.) |
| S6 | Crash reporter (local only) | P1 | Writes a local log; **no network transmission**. |

## 9. Framework package system

| # | Feature | Pri | Acceptance |
|---|---------|-----|------------|
| F1 | SPI defined in `core/spi` | P0 | Compose package implements it; `core` has no Compose dependency. |
| F2 | Static package registration | P0 | Phase 1: compile-time wiring only. |
| F3 | Architecture test enforcing the boundary | P0 | Build **fails** if `core` imports Compose. |
| F4 | Dynamic JAR loading | P3 | Phase 5 only, and only after `SECURITY.md` §4 is satisfied. |
| F5 | Package manager UI | P3 | Phase 5. |
| F6 | Package SDK + docs | P3 | Phase 5. |

## 10. Explicit non-features for v1

Recorded so they're decisions, not oversights:

- Round-trip parsing of hand-written Compose (see `PROJECT_PLAN.md` §7.1)
- Visual state management / data binding
- Navigation graph editing
- Backend, API, or database integration
- Real-time collaboration
- AI-assisted generation
- Cloud sync, accounts, telemetry
- Animation timeline editing
- Plugin marketplace

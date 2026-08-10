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
| C6 | Device preview frames | P0 | Selectable viewport profiles (desktop sizes in Phase 1); canvas clips to the frame. |
| C7 | Drag to reparent/reorder | P0 | Dragging a node shows a drop indicator; illegal drops (into a non-container, into own descendant) are rejected visually. |
| C8 | Per-node error isolation | P0 | A node that throws during render shows an error placeholder; the rest of the canvas keeps working. |
| C9 | Empty-container affordance | P0 | Zero-size containers render a minimum editor-only target so they remain selectable. |
| C10 | Multi-select | P1 | Shift/ctrl-click; shared property edits apply to all selected. |
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
| P6a | Custom user components in palette | P1 | User-defined components appear alongside built-ins. |

### Phase 1 component set (deliberately small)

**Layout:** `Column` · `Row` · `Box` · `Spacer` · `Surface` · `Card` · `Scaffold` (slots) ·
`LazyColumn` (static children only) · `LazyRow` (static children only)

**Content:** `Text` · `Image` · `Icon` · `Divider` / `HorizontalDivider`

**Input:** `Button` · `OutlinedButton` · `TextButton` · `IconButton` · `TextField` ·
`OutlinedTextField` · `Checkbox` · `Switch` · `RadioButton` · `Slider`

**Navigation (structural only):** `TopAppBar` · `BottomAppBar` · `NavigationBar` (Phase 2)

> Resist expanding this list before M9. Breadth is easy and shallow; the exit criteria demand depth.

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

## 5. Document & history

| # | Feature | Pri | Acceptance |
|---|---------|-----|------------|
| D1 | New / open / save / save-as | P0 | `.vforge` round-trips losslessly. |
| D2 | Undo / redo | P0 | Correct across ≥50 mixed operations; verified by property-based test. |
| D3 | Gesture coalescing | P0 | A drag or slider scrub is one history entry, not hundreds. |
| D4 | Autosave + crash recovery | P0 | Timer-based sidecar; on next launch, offer to restore. |
| D5 | Copy / paste / duplicate | P0 | Fresh IDs generated; paste targets current selection. |
| D6 | Multiple screens per project | P0 | Screen switcher; each exports its own composable. |
| D7 | Reusable user components | P1 | Extract selection → component; instance references update on edit. Cycle detection enforced. |
| D8 | Recent projects list | P1 | — |
| D9 | Schema migration on load | P0 | Older files migrate with a backup written first. |

## 6. Theming

| # | Feature | Pri | Acceptance |
|---|---------|-----|------------|
| H1 | Theme editor (colors/typography/shapes/spacing) | P0 | Edits apply live across the canvas. |
| H2 | Light/dark pairs | P0 | Toggle canvas between modes; both values stored. |
| H3 | Token references from props | P0 | See I4. |
| H4 | Theme codegen | P0 | Emits a usable `MaterialTheme` wrapper. |
| H5 | Token rename propagation | P1 | Renaming a token updates all references. |
| H6 | Import theme from existing Kotlin | P3 | Requires parsing — deliberately deferred. |

## 7. Code generation & export

| # | Feature | Pri | Acceptance |
|---|---------|-----|------------|
| G1 | Generate Kotlin for all supported nodes | P0 | Golden test per component and modifier. |
| G2 | Generated output compiles | P0 | **CI compiles the generated fixtures.** Not just string comparison. |
| G3 | Live code preview panel | P0 | Side-by-side view updating with selection; read-only in v1. |
| G4 | Export loose `.kt` files | P0 | To a user-chosen directory, with overwrite confirmation. |
| G5 | Export runnable Gradle project | P0 | Scaffolds a Compose Desktop project that runs with `./gradlew run` unmodified. |
| G6 | Generated-file header | P0 | Names source file, schema version, and warns about hand-edits. |
| G7 | Formatting pass on output | P0 | A formatting pass makes output idiomatic, not merely valid (M7: a deterministic normalizer strips KotlinPoet's redundant `public`; KotlinPoet handles imports/indent/wrap. ADR-019). |
| G8 | Copy composable to clipboard | P1 | For pasting into an existing project. |
| G9 | Per-target source-set routing | P1 | Phase 2+: files land in `commonMain` vs `androidMain` correctly. |
| G10 | Regeneration into an owned directory | P1 | Wholesale regeneration is safe and doesn't clobber user files. |

## 8. Editor shell

| # | Feature | Pri | Acceptance |
|---|---------|-----|------------|
| S1 | Dockable/resizable panels | P0 | Layout persists across sessions. |
| S2 | Keyboard shortcuts | P0 | Standard set; discoverable in menus. |
| S3 | Editor light/dark theme | P1 | Independent of the *project's* theme — don't conflate them. |
| S4 | Command palette | P2 | — |
| S5 | Preferences | P1 | Autosave interval, history depth, default export path. |
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

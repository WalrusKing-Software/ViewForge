# ViewForge — Technical Notes

Known-hard problems and the chosen approach for each. Read this before implementing the relevant
area; each of these has a non-obvious failure mode that will otherwise be discovered the expensive
way.

---

## 1. Modifier chain order is semantic

`Modifier.padding(8.dp).background(Red)` and `Modifier.background(Red).padding(8.dp)` render
differently — the first pads *then* fills, the second fills *then* pads. Order is not a stylistic
detail.

**Consequences:**
- The IR stores modifiers as an **ordered list**, never a map.
- The inspector must expose ordering and allow drag-reordering.
- Codegen must emit in exactly the stored order.
- Golden tests must include order-permutation cases; a test suite that only checks *which* modifiers
  are present will pass while the output is wrong.

**Additional trap:** some modifiers are only legal in specific scopes. `weight` requires
`RowScope`/`ColumnScope`; `align` requires a scope that provides it. Validation must be
parent-aware, and the palette/inspector should not offer scope-illegal modifiers. Getting this wrong
produces code that fails to compile — the worst failure mode for this product.

---

## 2. Canvas/output divergence

The canvas interprets the IR; the output is generated code. They *should* produce identical results,
but nothing structurally guarantees it. Any bug in either path shows up as "the editor lied."

**Approach:**
- Component definitions own both the renderer and the codegen emitter, side by side, so a change to
  one prompts a change to the other.
- CI screenshot-diff: render a fixture on the canvas, compile and render the generated output,
  compare within tolerance.
- Treat any divergence as a **P0 bug** regardless of severity. The product's entire value claim is
  "what you see is what you get."

**Known acceptable divergences** (document them in-app rather than pretending they don't exist):
- Font rendering differs slightly across OSes.
- `RawExpression` props can't be evaluated on the canvas.
- Platform-specific behavior (Android insets) can only be approximated in a desktop preview.
- **Interactive preview (C13, #120) is a deliberate divergence, gated off by default.** Normally the
  canvas draws inert inputs (a `Checkbox`'s `onCheckedChange` is a no-op, a `TextField` reflects its
  `value` prop but isn't editable) so it mirrors the generated code. When the user turns on interactive
  preview, the renderer's stateful inputs carry their own local `remember` state and live callbacks so the
  UI can be clicked/typed/toggled. This flows through `CanvasRenderer.Render(interactive)` →
  `RenderContext.interactive`, which **defaults false** — codegen and the fidelity screenshot tests never
  set it, so the "what you see is what you get" guarantee for *generated output* is unaffected; only the
  editor's live run-mode canvas opts in.

---

## 3. Zero-size and invisible nodes

An empty `Column`, a `Spacer` without size, or a node with `alpha = 0f` occupies no clickable area.
Users will create these constantly and then be unable to select them.

**Approach:**
- The tree panel is a complete, always-available selection surface — never canvas-only selection.
- In edit mode, containers with no measurable size render a minimum placeholder outline. **Edit mode
  only** — it must never affect generated output or the layout of siblings.
- The selection overlay draws a marker at the node's position even at zero size.

---

## 4. Structural sharing and recomposition cost

Every keystroke in a text prop creates a new document. Naive `copy()` of the whole tree means O(n)
allocation per keystroke and, worse, breaks Compose's ability to skip unchanged subtrees — because
every node is a new object with a new identity.

**Approach:**
- Rebuild only the root-to-changed-node path; all other subtrees keep referential identity.
- Ensure IR types are stable/immutable so Compose can skip them.
- Key list children by node ID so reorders move rather than recreate composables.
- Benchmark with a deliberately large tree (500+ nodes) before declaring this done.

---

## 5. Hit-testing under zoom and pan

Bounds captured via `onGloballyPositioned` are in canvas coordinate space. Pointer events on the
overlay are in screen space. Zoom and pan mean these are not the same, and a naive implementation
works perfectly at 100% zoom and silently breaks everywhere else.

**Approach:**
- One canonical transform (canvas ↔ screen) applied in one place. Never inline coordinate math at
  call sites.
- Test hit-testing explicitly at multiple zoom levels and pan offsets.
- Bounds must be invalidated on recomposition — a stale index selects the wrong node after a layout
  change.

**Implemented (C5, issue #38):** the transform is a single `graphicsLayer` on the rendered frame,
driven by the pure `CanvasViewport(zoom, panX, panY)` view state (`editor/state`). Because
`graphicsLayer` participates in Compose's layout-coordinate chain, `boundsInWindow` returns node
bounds already scaled/panned, and the `SelectionOverlay` — left *unscaled* on top so its outlines
keep constant thickness — reconciles pointer events against them in **window space**. That is why
`hitTest` needed no change: window space is the common frame both sides already agree in. The pure
`CanvasViewport` math (clamp/step/pan) is unit-tested (`CanvasViewportTest`); end-to-end zoom
hit-testing rests on Compose's layer-aware coordinates and is best confirmed with a UI/screenshot
test (not yet added). Gestures live only in the overlay (scroll → zoom, space-drag → pan); the shell's
`handleShortcut` tracks the space bar and binds Ctrl +/−/0.

**Refinement (#116):** node bounds are now stored in the frame's **unscaled content space** (captured
against a reference box *below* the `graphicsLayer`), and the overlay applies the transform explicitly
through the pure `contentToScreen`/`screenToContent`/`contentRectToScreen` helpers (`Selection.kt`,
unit-tested in `CanvasTransformTest`) when it draws and when it maps a pointer. This is the single
canonical transform; any new overlay that draws per-node chrome reuses it rather than re-deriving
coordinate math. The debug container-border overlay (**#117**, a View-menu toggle outlining every layout
container via `containerNodes` + `contentRectToScreen`) is the first such reuse; the measure/spacing
overlay (**#119**, hold **M** to show the pure `measureGaps` distances from the selection to its
container edges, held-key-tracked like space-pan) is the second; the static alignment guides (**#118**, a
View toggle drawing the pure `alignmentGuides` lines where the selection's edges/centre meet a sibling or
the parent) are the third. Note #118 is C11 **reinterpreted** for the container-layout model: free-move
snapping-during-drag presumes absolute positioning the canvas doesn't have (drag is reparent-only), so
that part is split to the blocked #129 rather than forcing a positioning model the layout doesn't use.

---

## 6. Drag-and-drop validity rules

Dropping a node into an invalid parent produces code that doesn't compile.

**Rules to enforce, with visual feedback during the drag (not an error afterward):**
- Cannot drop a node into itself or any descendant (cycle).
- Cannot drop into a component that doesn't accept children.
- Slot-based components accept drops only into declared slots.
- Some components constrain child types.
- Scope-dependent modifiers on the dragged node may become invalid in the new parent — either strip
  them with a warning or block the drop, but never silently produce broken output.

---

## 7. Undo/redo across selection and document state

Undo restores the document, but selection may reference nodes that no longer exist.

**Approach:**
- Commands capture the selection state needed to restore sensibly.
- After any undo/redo, prune selection to nodes that exist.
- Deleting a node selects its parent, not nothing — losing selection entirely is disorienting.
- Property-based test: random command sequences, then N undos and N redos must return an equal
  document.

---

## 8. Text measurement and fonts

Text layout depends on the font, which depends on the platform. A desktop canvas previewing an
Android target will not measure text identically to the device.

**Approach:**
- Bundle the fonts used in previews where licensing allows, so at least the editor is consistent
  across machines.
- Document this as a known divergence.
- For Phase 2, prefer explicit font declarations in the theme over relying on platform defaults.

---

## 9. KotlinPoet formatting behavior

KotlinPoet 2.x changed wrapping: **spaces no longer wrap automatically** when a line exceeds the
length limit. The `♢` placeholder marks spaces that are safe to wrap. The older `·` non-breaking
space marker still exists but is now equivalent to a plain space.

**Consequence:** naive emission produces very long lines. Compose code with chained modifiers hits
this constantly.

**Approach:**
- Use `♢` deliberately at safe wrap points in emitter templates.
- Run a formatter (ktlint/spotless) over output as a final pass rather than fighting KotlinPoet's
  layout.
- Lock the result with golden files so formatting regressions are caught.

---

## 10. Compose Multiplatform version churn

CMP ships frequently, with real deprecations landing in recent releases: unified `@Preview`
annotation (older ones deprecated), Navigation 3 replacing `PredictiveBackHandler`, v2 UI test APIs
becoming default with v1 deprecated, and Gradle dependency aliases (`compose.ui` etc.) deprecated in
favor of direct version-catalog references.

**Approach:**
- Pin exact versions; use direct library references in the version catalog rather than the
  deprecated aliases.
- Upgrade deliberately, one version at a time, on a schedule — never mid-feature.
- Because generated code must compile against *the user's* CMP version, record the targeted CMP
  version in project settings and in the generated file header. This will eventually require
  version-conditional codegen; don't build that until it's actually needed, but don't design in a
  way that makes it impossible either.

---

## 11. Compose Hot Reload

Recent CMP releases bundle Compose Hot Reload with the Gradle plugin, enabled by default for desktop
targets. This is useful for developing **ViewForge itself** — the editor is a Compose Desktop app,
and iterating on editor UI without full restarts is a meaningful speedup.

It is **not** a mechanism for previewing user projects (that's the interpreted canvas). Don't
conflate the two.

---

## 12. JVM/JDK constraints

- Compose Desktop requires **JDK 11+** due to memory management in the Skia bindings.
- **JDK 17+ is required to package native distributions.**
- Standardize on **JDK 21** (an LTS ≥ 17) for both development and CI to avoid a split-toolchain
  situation. Pinned in `gradle/libs.versions.toml` (`jdk = "21"`) and applied via `jvmToolchain`
  in the `viewforge.kotlin-library` convention plugin (M0).
- If Jewel is adopted for IDE-style chrome, it requires the **JetBrains Runtime (JBR)** specifically
  rather than a stock JDK — a real constraint on the build and distribution pipeline. Decide before
  adopting, not after.

---

## 13. iOS verification gap

Codegen for iOS can be written and unit-tested on any OS. **Visual verification cannot** — building
and running iOS output requires macOS with Xcode.

**Approach:**
- Write and test iOS codegen when convenient; keep it behind a clearly-labeled experimental flag.
- **Do not advertise iOS support until verified on real hardware.**
- Note also that Compose Multiplatform raised its minimum supported iOS version (13.0 → 14.0) in a
  recent release, and dropped Apple x86_64 targets — check current constraints when Phase 3 starts
  rather than trusting anything written here.

---

## 14. Testing generated code properly

String comparison alone is insufficient — code can match a golden file and still be wrong, or differ
harmlessly in whitespace and fail a test for no reason.

**Layered approach:**
1. **Golden files** for structure and formatting (fast, catches regressions).
2. **Compilation** of generated fixtures in CI (catches invalid code — the highest-value check).
3. **Screenshot diff** of compiled output vs. canvas (catches semantic divergence).

All three are needed. Each catches a class the others miss.

---

## 15. Wasm/browser target build constraints

The `wasmJs` target cannot be verified by the in-process kctfork compile gate — kctfork is JVM-only and
`CanvasBasedWindow` is not on the desktop classpath. The CI gate is therefore split across two tiers (ADR-040):

1. **Structural tests** in `:packages:compose:test` (`codegen-verify` on Forgejo) — check source-set routing,
   KotlinPoet output, the `index.html` canvas element, and the Gradle `wasmJs {}` block. No browser required.
2. **`wasm-smoke` workflow** — exports a live fixture via `WasmFixtureExporter`, runs
   `wasmJsBrowserDistribution` (build + webpack, Forgejo homelab), and `wasmJsBrowserTest` via Karma +
   headless Chrome (GitHub Actions `ubuntu-latest` — Chrome is guaranteed there, not on the homelab runner).

The fixture is generated from the live exporter at CI time, never hand-maintained, so structural regressions
in the exporter surface before the fixture reaches the browser step.

**`CanvasBasedWindow` is `@ExperimentalComposeUiApi`.** Generated `wasmJsMain/Main.kt` files emit
`@file:OptIn(ExperimentalComposeUiApi::class)` so the entry compiles without the caller opting in. This
annotation requirement may change as the API stabilises — check when upgrading CMP.

**No `wasmJsMain.dependencies {}` block needed.** The Compose Multiplatform plugin pulls the required
libraries from `commonMain`'s `compose.*` dependencies — adding an explicit block would be noise.

---

## 16. Responsive override invariant: the stranded-override state

A responsive override is **stranded** when a node has an override for a prop at a given breakpoint
(e.g. `responsive["expanded"]["fontSize"]`) but no corresponding base value (`props["fontSize"]` absent).
Stranded overrides cause two problems:

- The canvas has no base to fall back to at narrower breakpoints — the prop silently disappears at `compact`.
- Codegen emits a `BoxWithConstraints` block that assigns the overridden value but has no base assignment,
  a runtime logic error in the generated code.

**Prevention at edit time.** The inspector guards against creating stranded overrides: `SetResponsiveOverride`
is wrapped in a `CompositeCommand` that also sets the base prop to the same value when the base is absent
(#387). Both the override and the auto-filled base undo atomically together.

**Detection when stranded overrides already exist.** `ComposeAdvisories.forNode()` checks `node.responsive`
against `node.props` for each breakpoint entry and surfaces a `MissingResponsiveBase` advisory with one
`AdvisoryFix.ClearResponsiveOverride(breakpointId, propName)` per stranded key (#383). The inspector
renders each fix as a clickable "clear bp: propName" link (undoable, calls `setResponsiveOverride(..., null)`)
(#385).

**Why not a migration?** Existing `.vforge` files with stranded overrides (possible before #387) are handled
through the advisory path rather than migration: the override data itself is not wrong — only the missing base
is — and silently auto-filling it on load would be an irreversible mutation without user awareness.

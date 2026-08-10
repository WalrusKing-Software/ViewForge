# ViewForge — Architecture Decision Records

Short records of non-obvious decisions, including what was rejected and why. Add one whenever a
choice would otherwise be re-litigated later.

Format: **Context → Decision → Consequences → Status**

---

## ADR-001 — Desktop application, not web

**Status:** Accepted

**Context.** The editor could be a Compose Desktop app, a browser app backed by a local process, or a
hybrid shell embedding a webview.

**Decision.** Native Compose Multiplatform desktop application (JVM).

**Rationale.** Because Compose is the first and primary supported framework, a Compose Desktop
editor renders the target framework natively at zero integration cost. A browser editor would need
Compose-in-Wasm bridging — real work on a less mature foundation. Filesystem access for reading and
writing Kotlin projects is trivial natively and requires a companion process in a browser.

**Rejected: browser-based.** Better zero-install reach and a more natural future path for JS
frameworks, but it inverts the cost structure for the framework that actually matters now.

**Rejected: hybrid with embedded webview.** Compose Desktop has no built-in web engine; embedding
one means JCEF/KCEF — roughly 100 MB+ and real platform-specific integration pain. Unjustifiable for
a feature (JS framework support) explicitly deferred to Phase 5.

**Consequences.** Accepts per-OS packaging and signing burden. Accepts that Phase 5 JS-framework
support will require revisiting the shell — likely embedding a browser engine at that point. The
core IR, codegen, and component model carry over regardless, which is where the real value is.

---

## ADR-002 — Interpreted canvas, not compiled preview

**Status:** Accepted

**Context.** The canvas could compile generated Kotlin and render the result, or interpret the IR
directly with real composables.

**Decision.** Interpret the IR at runtime.

**Rationale.** Compiling on each edit is orders of magnitude too slow for a WYSIWYG loop. It would
also require locating or bundling a JDK, Kotlin compiler, and Gradle — inflating app size and
complicating the future package story.

**Consequences.** Canvas and generated output can drift. This is mitigated by co-locating renderer
and emitter per component and by making screenshot-diff testing a Phase 1 exit criterion. The
divergence risk is real and permanent; it must be actively defended against, not assumed away.

---

## ADR-003 — No round-trip parsing in v1

**Status:** Accepted

**Context.** Tools like Onlook edit existing codebases bidirectionally. Doing this for Compose would
be the headline feature.

**Decision.** v1 is one-way. The `.vforge` file is the source of truth; `.kt` is output.

**Rationale.** Parsing arbitrary Kotlin into a constrained IR is a compiler-grade problem. Real
codebases contain control flow, custom composables, and computed values with no IR representation.
Onlook's approach works partly because the browser DOM provides a live, inspectable tree mapped to
source locations; Compose has no cross-target equivalent (notably, Compose for Web renders to a
canvas, not a DOM).

**Consequences.** Cannot open an existing Compose project and edit it visually — a genuine
limitation to state plainly rather than obscure. If demanded later, the tractable version is a
narrow marked region (`// region ViewForge`) regenerated wholesale, not general parsing.

---

## ADR-004 — Phase order: Desktop → Android → iOS → Web

**Status:** Accepted

**Context.** Compose Multiplatform supports four targets at differing maturity, with differing
hardware requirements.

**Decision.** Desktop first, then Android, then iOS, then Web.

**Rationale.** Desktop is the editor's own platform (free dogfooding) and needs no extra toolchain.
Android is mature and buildable on any OS. **iOS requires macOS + Xcode to build and verify** —
hardware the developer does not currently have, making it impossible to validate. Compose for Web
(Kotlin/Wasm) is the least mature target, with APIs and tooling still moving.

**Consequences.** iOS support is blocked on hardware, not effort. iOS codegen may be written early
but must not be advertised until verified on real hardware.

---

## ADR-005 — Ordered modifier list in the IR

**Status:** Accepted

**Context.** Modifiers could be modeled as a map (name → value) or an ordered list.

**Decision.** Ordered list, with stable per-entry IDs.

**Rationale.** Compose's `Modifier` chain is non-commutative — order changes rendering. A map would
make entire categories of layout unreachable and cause the canvas to misrepresent output.

**Consequences.** The inspector must expose ordering and support reordering — more UI complexity
than a property grid, and non-negotiable. Golden tests must include order-permutation cases.

---

## ADR-006 — Typed PropValue union, not strings

**Status:** Accepted

**Decision.** `PropValue` is a sealed hierarchy: `Literal`, `ThemeRef`, `ResourceRef`,
`RawExpression`, and reserved `StateBinding`.

**Rationale.** Strings would force re-parsing at every consumer (inspector, validator, renderer,
emitter) with inconsistent results. Typed values let the inspector be fully data-driven and let theme
token renames propagate automatically.

**Consequences.** Slightly more verbose JSON. Adding a new value kind is a schema change. Both are
acceptable prices.

---

## ADR-007 — SPI defined immediately, one implementation only

**Status:** Accepted

**Context.** The long-term goal is multi-framework support. Designing the abstraction now risks
guessing wrong; deferring it entirely risks Compose assumptions permeating the core.

**Decision.** Define the SPI in `core/spi` from day one and route everything through it, but write
**only one implementation** (Compose) until Phase 5. Enforce the `core` ↔ package boundary with a
build-failing architecture test.

**Rationale.** The boundary's value is preventing framework-specific leakage into core. The
abstraction's *shape* should be revised against a real second implementation, not guessed with one
data point.

**Consequences.** The SPI will almost certainly change when framework #2 arrives — that's expected
and fine. Some indirection cost in Phase 1 with no immediate payoff, accepted as the price of
keeping the option open.

---

## ADR-008 — Compose Multiplatform is one package with four targets

**Status:** Accepted

**Decision.** Model CMP as a single framework package exposing multiple `TargetDefinition`s, not as
four packages.

**Rationale.** Mirrors KMP's own source-set model (`commonMain`, `androidMain`, `iosMain`,
`jvmMain`, `wasmJsMain`). The component model is shared across targets; only export routing and
preview profiles differ.

**Consequences.** Establishes package ≠ platform, which matters later (a React package would
similarly need web and React Native targets).

---

## ADR-009 — Editor instrumentation for hit-testing, not the semantics tree

**Status:** Accepted

**Decision.** Capture node bounds via `Modifier.onGloballyPositioned` injected into each node's
modifier chain; resolve clicks through a spatial index on a transparent overlay.

**Rationale.** The semantics tree is designed for accessibility and testing; it does not map 1:1 to
IR nodes (some composables emit none, others merge), and it is a framework-specific concept other
packages won't share.

**Consequences.** Zero-size nodes are unselectable by geometry — mitigated by the tree panel and an
edit-mode minimum-size affordance. Coordinate transforms must be handled centrally to survive zoom
and pan.

---

## ADR-010 — KotlinPoet for code generation

**Status:** Accepted

**Decision.** Generate via KotlinPoet 2.x's structural API rather than string templates.

**Rationale.** Structural emission manages imports automatically, escapes literals correctly, and
makes malformed output much harder to produce. String concatenation would reintroduce a class of
bugs that is a *security* concern (GC-1/GC-2 in `SECURITY.md`), not just a quality one.

**Consequences.** KotlinPoet 2.x's changed wrapping behavior (no automatic space wrapping; `♢`
marks safe wrap points) needs deliberate handling, backed by a formatter pass over output.

---

## ADR-011 — No network access in v1

**Status:** Accepted

**Decision.** The v1 application makes no network requests. No telemetry, no accounts, no sync, no
update check, no AI features.

**Rationale.** Eliminates entire threat categories, simplifies the security posture to essentially
"local file handling," and removes privacy questions. None of it is needed for Phase 1.

**Consequences.** No auto-update (users download new versions manually) and no crash reporting. Any
future network feature is a security-relevant change requiring a documented threat-model update.

---

## ADR-012 — Git Flow branching with a protected `main`

**Status:** Accepted

**Context.** Two models were considered: trunk-based development (short-lived branches merging
directly to `main`) and Git Flow (`feature/*` → `develop` → `release/x.y.z` → `main`).

**Decision.** Git Flow. `main` contains only released, stable code; `develop` is the integration
branch; `release/x.y.z` branches stabilize a version and are tagged on merge to `main`.

**Rationale.** The guarantee that **`main` never contains in-progress or experimental work** is
worth the extra ceremony for this project specifically. ViewForge produces installers that users
download and run, and a distributable desktop app benefits from an unambiguous "this is what
shipped" branch. Release branches also provide a stabilization window where only release-blocking
fixes land, and a clean base for cutting hotfixes against a released version.

**Rejected: trunk-based.** Lower overhead and fewer merge steps, which suits a solo developer. But
it makes `main` a moving integration target, so "what's currently released" is only recoverable from
tags rather than being a property of the branch itself.

**Consequences.** More merge steps per change, and two permanent branches to keep protected. The
**back-merge from `release/*` and `hotfix/*` into `develop` is a required step that is easy to
forget** — skipping it silently reintroduces already-fixed bugs, so it is written into the release
checklist rather than left to memory. Agents must be told to branch from `develop`; branching from
`main` by default would violate the model.

---

## ADR-013 — The `@Composable` renderer seam lives in `editor/canvas`, not `core/spi`

**Status:** Accepted

**Context.** ARCHITECTURE §6.2 sketches `ComponentRenderer` alongside the other SPI interfaces in
`core/spi`. But a renderer that draws real Compose must be `@Composable`, and `@Composable` is a
Compose type — putting it in `core/spi` would violate the non-negotiable rule that `core` never
depends on Compose (CLAUDE.md rule 1, ADR-007), which an architecture test is meant to enforce. The
docs already flag the split ("only the renderer half needs the framework"); M2 (the first UI code)
forced the question of *where* that half lives.

**Decision.** Keep `core/spi` Compose-free (schema/data only, still just a marker through M2). The
Compose-typed rendering seam is a tiny `fun interface CanvasRenderer { @Composable fun Render(root:
Node) }` in `editor/canvas`. The actual walk and per-component renderers live in `packages/compose`
(`ComposeRenderer` / `RenderNode`). `:app` — the one module allowed to name the Compose package
(ARCHITECTURE §3) — binds the two in `Main.kt`.

**Rationale.** The editor drives rendering through a type it owns, and never imports the Compose
package. `packages/compose` need not depend on the editor (avoiding a wrong-direction/cyclic
dependency), because `:app` does the wiring. One implementation before one abstraction (ADR-007): no
component/renderer *registry* SPI is introduced until it is actually needed (M3+), so the seam stays
a single function.

**Rejected.** (a) A `@Composable` interface in `core/spi` — impossible without a Compose dependency
in core. (b) A registry interface in `editor/canvas` implemented by `packages/compose` — makes the
framework package depend on the editor and pre-commits an abstraction shape M3 would rework.

**Consequences.** When a second framework or dynamic package loading arrives (Phase 5), this seam
grows into the richer `ComponentRenderer` registry the SPI sketch anticipates — revised against a
real second implementation rather than guessed now. Until then, adding a component is a local change
in `packages/compose`.

---

## ADR-014 — `@Composable` naming exemption for ktlint

**Status:** Accepted

**Context.** M2 introduced the first Compose UI code. Composable functions are PascalCase by Compose
convention (they emit UI rather than compute values), which the standard ktlint `function-naming`
rule rejects, failing `spotlessCheck`.

**Decision.** Exempt `@Composable`-annotated functions from `function-naming` via
`ktlint_function_naming_ignore_when_annotated_with = Composable`, set as an `editorConfigOverride`
in the `viewforge.kotlin-library` convention plugin.

**Rationale.** Matches the near-universal Compose convention. The override is set in the convention
plugin rather than `.editorconfig` because spotless 6.x did not reliably load the property from
`.editorconfig` in testing; the convention plugin is the authoritative place for lint rules anyway
(it already pins the ktlint version). Harmless for `core/*`, which has no composables.

**Consequences.** One override key to carry. If spotless/ktlint later load it cleanly from
`.editorconfig`, this can move there for locality.

---

## ADR-015 — Component catalog is an editor-owned seam; tree-panel DnD is M4's drag surface

**Status:** Accepted

**Context.** M4 (mutation & history) needs two framework-dependent facts the editor must not hardcode:
the list of components to offer in the palette, and, per type, whether it accepts children / which
slots it has (for drop validation). ARCHITECTURE §6.2 sketches this as `ComponentDefinition` in the
SPI, but ADR-007/ADR-013 caution against putting framework abstractions in `core` prematurely or
letting `packages/compose` depend on the editor. M4 also has to pick a *drag* surface for
reorder/reparent: the canvas (C7, geometric) or the tree/layers panel (T2).

**Decision.** (a) Define a minimal, Compose-free `ComponentCatalog` interface (+ `PaletteEntry`) in
`editor/state` — palette list, `newNode(type)`, `acceptsChildren`, `slotsOf`. `packages/compose`
exposes its catalog as plain data (`ComposeComponents`) using its own types; `:app` adapts that to
`ComponentCatalog`, exactly as it adapts `ComposeRenderer` to `CanvasRenderer` (ADR-013). The catalog
lists **only the currently renderable component set**, so the palette can never add a node the canvas
can't draw. (b) M4 ships **tree-panel drag-and-drop** (T2) as the drag surface. Add-via-palette is
**click-to-insert** into the current selection.

**Rationale.** Keeps `core` free of framework knowledge and preserves the dependency direction
(package never depends on the editor) without pre-committing the richer `PropDefinition` schema the
inspector will need — that arrives at M5, revised against real use (ADR-007). Tree DnD gives
unambiguous, well-defined drop targets (a flat row list with recorded bounds) and correct index
semantics, where canvas geometric DnD would need drop-zone math that must also survive future
zoom/pan — more risk for the same command/history core.

**Rejected.** `ComponentDefinition` in `core/spi` now (premature abstraction with one implementation,
ADR-007). `packages/compose` implementing the editor interface directly (wrong-direction dependency,
ADR-013). Canvas geometric drag as M4's surface (heavier and riskier; deferred as a focused
follow-up alongside palette-drag P2a).

**Consequences.** The palette and drop validation are fully data-driven and grow automatically as the
catalog grows (with the renderer, at M6). Canvas drag-to-reparent (C7) and drag-from-palette (P2a)
remain open P0 items for a later milestone. When a second framework or the M5 inspector arrives, this
seam grows into the fuller SPI schema — revised against real use, not guessed.

---

## ADR-016 — `PropDefinition` schema lives in `core/model`; the inspector is fully data-driven

**Status:** Accepted

**Context.** M5 makes the inspector edit props and modifiers with typed controls, generated from a
schema (`PropDefinition`/`PropType`, `ModifierDefinition`) rather than per-component UI (I1). That
schema is framework knowledge, but it is also pure data that both the framework package (which
authors it) and the editor (which renders controls from it) need. ADR-015 put the smaller
`ComponentCatalog`/`PaletteEntry` seam in `editor/state` and had `:app` adapt the compose package's
own plain data — which duplicated the shape. Where should the richer prop schema live?

**Decision.** Put `PropType`, `PropDefinition`, `ModifierArg`, and `ModifierDefinition` in
`core/model` as pure, non-serialized data. `packages/compose` builds real `PropDefinition`s (it
already depends on `core/model`), the `ComponentCatalog` interface in `editor/state` returns them, and
`:app`'s adapter passes them straight through with no mapping. The inspector iterates
`catalog.propsFor(type)` and `catalog.modifierCatalog` and dispatches purely on `PropType` — **no
per-component branching**.

**Rationale.** These types reference `PropValue`, which is already in `core/model`, and DATA_MODEL §6
sketches `PropDefinition` as core schema. Homing them there removes the ADR-015 duplication and keeps
the dependency direction clean (package → core, editor → core; package never depends on the editor).
They are **not** `@Serializable`: they describe the package's runtime capabilities, not the persisted
document, so they never enter the `.vforge` format or need migration.

**Rejected.** Duplicating the schema in `packages/compose` and mapping in `:app` (needless
boilerplate, drift risk). Putting it in `core/spi` (still a marker module; would pull richer types
into the SPI before a second framework justifies the shape, ADR-007).

**Consequences.** Adding a component or modifier is data-only: author its schema in the compose
catalog and the inspector picks it up. The editor-owned `ComponentCatalog` *interface* stays the seam
(ADR-013/015); only the data types moved to core. Enum value lists in the catalog must stay in lockstep
with the renderer's parsers (`render/Values.kt`), enforced by the honesty rule, not the compiler.

---

## ADR-017 — Undo coalescing via `Command.coalesceKey`

**Status:** Accepted

**Context.** A slider/stepper drag or typing into a text field emits many `SetProp`/`SetModifierArg`
commands per second. Recording each as its own history entry (D3) makes undo useless — one Ctrl+Z
should reverse the whole gesture, not one keystroke. The M4 `History` recorded every command
verbatim.

**Decision.** Add `val coalesceKey: Any? get() = null` to `Command`. When an executed command has a
non-null key equal to the top undo entry's key **and the redo stack is empty**, `History.execute`
merges them: it keeps the *original* entry's inverse (so undo reverts the entire run) and swaps in the
latest command (so redo reaches the final state). `SetProp` keys on `(nodeId, "prop", key)`,
`SetModifierArg` on `(nodeId, modifierId, key)`; structural commands leave the key null and never
coalesce.

**Rationale.** A single captured inverse plus the latest command is exactly the endpoints of the run,
which is all a linear history needs. Keying by target means switching to a different prop/node/modifier
naturally starts a fresh entry — no explicit begin/end transaction bracketing the gesture, which the
inspector controls would otherwise have to signal on drag start/stop.

**Rejected.** Explicit transactions (begin/commit around a gesture) — more plumbing through every
control for the same result. Time-window coalescing — fragile and surprising across pauses.

**Consequences.** Continuous edits are one undo step and the canvas still updates live per keystroke.
Coalescing is deliberately conservative (interrupted by any differently-keyed command or an undo), so
it never silently swallows a distinct edit. `CompositeCommand` remains for genuinely atomic multi-part
edits (e.g. cut); the two mechanisms are complementary.

---

## ADR-018 — Codegen v1: golden file as contract, in-process compile gate, renderer-married emission

**Status:** Accepted

**Context.** M6 turns the IR into Kotlin/Compose source. Three things had to be pinned: how output is
proven correct, how the emitter stays faithful to the canvas, and a few formatting/ordering choices
that are otherwise arbitrary and would drift.

**Decision.**
- **Emission mirrors the renderer.** Codegen reuses the interpreter's *same* pure value layer
  (`render/Values.kt` — the color parser, alignment/arrangement parsers, `paddingSpec`/`sizeSpec`),
  and each component/modifier emitter is written beside its renderer with the same argument order.
  The generated tree is the drawn tree (TECHNICAL_NOTES §2). A `CatalogConsistencyTest` fails the
  build if a catalog enum value has no matching emission, so the three can't silently diverge.
- **Golden file is the contract.** Every `.vforge` fixture under `resources/golden/` asserts
  byte-for-byte against a committed `.kt`, covering every supported component and modifier plus a
  modifier-order permutation. Fixtures are exempt from spotless (they are output, not source).
- **Compilation is the real gate.** `CompilationTest` compiles the generated source in-process with
  kotlin-compile-testing (kctfork) and the Compose compiler plugin registered, asserting `OK`
  (G2/GC-6). This is what `codegen-verify.yml` runs; string equality alone can pass on uncompilable
  output (TECHNICAL_NOTES §14).
- **Root modifier order** = caller's `modifier` first, then the node's own chain
  (`modifier.fillMaxSize()…`), resolving DATA_MODEL §12.1 the Compose-conventional way.
- **KotlinPoet's explicit `public`** on generated functions is accepted as-is; KotlinPoet offers no
  toggle. Final visibility/format normalization is the G7 formatting pass, which lands with export
  (M7), not baked into M6 goldens.

**Rationale.** Reusing the render value layer makes canvas/codegen divergence a compile error rather
than a discipline problem. The layered golden + compile check catches the two distinct failure
classes (formatting regression vs. invalid code) the milestone requires. In-process kctfork keeps the
whole gate inside `:packages:compose:test`, so no separate CI job or Gradle fixtures module is needed.

**Rejected.** A separate Gradle module that compiles emitted fixtures against real Compose Desktop —
more faithful to a user build, but heavier and outside `:packages:compose:test`; revisit if kctfork's
in-process classpath proves insufficient. Building code by string templates — a security anti-pattern
(GC-1/GC-2). Emitting a broader component/modifier set now — deferred; depth over breadth until M9.

**Consequences.** Adding a component/modifier is a renderer + emitter + golden-fixture triple, and the
compile gate guarantees it actually builds. kctfork, the Kotlin compiler-embeddable, and the Compose
compiler plugin embeddable are new **test-only** pinned dependencies, forced to the catalog `kotlin`
version so the plugin matches the compiler it registers into. Generated output currently carries an
explicit `public` and KotlinPoet's import block until the G7 pass normalizes it at export.

---

## Template

```markdown
## ADR-NNN — <title>

**Status:** Proposed | Accepted | Superseded by ADR-NNN

**Context.** What forces are at play?

**Decision.** What was chosen?

**Rationale.** Why this over the alternatives?

**Rejected.** What else was considered, and why not?

**Consequences.** What becomes easier? What becomes harder? What did we accept?
```

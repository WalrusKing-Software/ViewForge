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

**Consequences.** Cannot open an existing *hand-written* Compose project and edit it visually — a genuine
limitation to state plainly rather than obscure. This exclusion is unchanged. A **narrow exception** for
re-opening output ViewForge itself generated — carried as an IR sidecar, recognised by ownership, never
parsed — is sanctioned separately in **ADR-032**; it does not reverse this decision. If parsing hand-written
Compose is demanded later, the tractable version is a marked region (`// region ViewForge`) regenerated
wholesale, not general parsing.

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
depends on Compose (the core-never-depends-on-Compose rule, ADR-007), which an architecture test is meant to enforce. The
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

## ADR-019 — Project export: bundled wrapper, lightweight formatter, split write path

**Status:** Accepted

**Context.** M7 exports a project two ways (FEATURES G4/G5): loose `.kt` files, and a full Compose
Desktop scaffold that must run with `./gradlew run` **unmodified**. Three things needed pinning: how
the scaffold supplies the binary Gradle wrapper, how the G7 formatting pass is done (ADR-018 deferred
it here), and where the write orchestration lives without breaking the module boundaries.

**Decision.**
- **Bundle the verified wrapper.** The scaffold ships this repo's own checksum-verified
  `gradle-wrapper.jar`, `gradlew`, and `gradlew.bat` as resources under `packages/compose`
  (`/scaffold/*`), copied verbatim on export. `gradlew` is written with the POSIX execute bit set so
  `./gradlew run` works on Unix without a manual `chmod`. Reusing our own wrapper satisfies "runs
  unmodified" while keeping to a known-good artifact (SECURITY DS-5).
- **A lightweight, deterministic G7 formatter**, not an embedded ktlint engine. KotlinPoet already
  manages imports, 4-space indentation, and wrapping; the one non-idiomatic artifact it emits is an
  explicit `public` (no toggle, per ADR-018). `KotlinFormatter` strips exactly that. It stays
  golden-testable with **no new runtime dependency** (SECURITY DS-6). The formatter is a seam: a
  richer implementation can replace `format()`'s body later.
- **Split the pipeline across the modules that own each concern.** The Compose desktop *target
  exporter* (`packages/compose/targets/DesktopExporter`) is pure — it assembles a bundle of
  `ExportFile`s (formatted screens built with KotlinPoet, scaffold text from `GradleScaffold`, and the
  wrapper binaries) and never touches disk. The *writer* (`core/project/ProjectExporter`) is
  framework-agnostic and drives every write through the existing `GuardedWriter`, so export inherits
  the same path-safety guarantees as project save (root confinement, traversal/symlink rejection,
  reserved-name checks, atomic replace — SECURITY §5). The shell triggers export through a Compose-free
  `ProjectExportService` seam in `editor/state` that `:app` binds to those two, exactly like the
  renderer/catalog wiring (ADR-013).
- **Scaffold build scripts are flat templates.** `build.gradle.kts`/`settings.gradle.kts` are Kotlin
  DSL but carry **no user prop values** — the only user-derived datum is the project name, reduced to a
  `[a-z0-9-]` slug — so KotlinPoet (the structural-codegen rule, aimed at the generated *UI* Kotlin) is neither
  necessary nor practical for them, and GC-2's injection concern does not arise. The generated screens
  and `Main.kt`, which *do* derive identifiers from user input, are built structurally with KotlinPoet.
- **Pinned scaffold versions are constants mirroring the catalog.** The version catalog is a
  build-time artifact with no runtime accessor, so the emitted Kotlin/Compose/Gradle/JDK versions live
  as constants in `GradleScaffold`, commented to be kept in lockstep with `libs.versions.toml`.

**Rationale.** Reusing the guarded writer means export gets path safety for free rather than
reimplementing it. Keeping the target exporter pure (bundle in, no I/O) matches the `CodeGenerator`
SPI contract and keeps the framework package off the disk. A targeted formatter delivers the one
normalization the emitter actually needs without dragging a CLI-oriented engine into the runtime.

**Rejected.** Embedding `ktlint-rule-engine` at runtime (a large dependency tree for one `public`
removal; DS-6). Synthesizing the wrapper jar or omitting it and telling users to run `gradle wrapper`
(fails "runs unmodified"). Putting the write orchestration in `packages/compose` (would make the
framework package do disk I/O, against the SPI's purity) or generalizing the SPI with target/export
types now (ADR-007 — no second framework to shape them against). Full byte-for-byte goldens for
`Main.kt` (asserted behaviorally instead; the screen files are pinned against the M6 golden minus
`public`, and the compile gate proves both still build).

**Consequences.** Export is end-to-end and dogfoodable from the toolbar. Adding a component/modifier
still costs only its renderer + emitter + golden (ADR-018); export picks it up for free. The bundled
wrapper artifacts must be refreshed when the repo's own wrapper is upgraded (they are byte-copies).
The minimal export UI runs synchronously on the UI thread — fine for the small file counts of Phase 1;
moving codegen/IO to `Dispatchers.IO` (ARCHITECTURE §8) is a later refinement. An actual
`./gradlew run` of an exported project is **not** verified in CI (it needs network to resolve Compose
artifacts); the in-process compile gate against real Compose (G2/GC-6) is the offline stand-in, and a
scaffold smoke build is a candidate for the M10 packaging matrix.

---

## ADR-020 — Theming: theme-as-command, project-driven canvas scheme, `AppTheme` codegen

**Status:** Accepted

**Context.** M8 makes the project theme editable (H1), previewable in light/dark (H2), referable from
props (H3, already shipped M5), rename-propagating (H5), and code-generatable (H4). Four things needed
pinning: how theme edits enter the undo model, how the *canvas* reflects the theme (not just props that
name a token), how a token rename reaches every reference, and how the generated `MaterialTheme`
wrapper is produced and wired.

**Decision.**
- **Theme edits are commands.** The theme is a top-level `Project.theme`, so a whole-value `SetTheme`
  (with the ADR-017 `coalesceKey` keyed by token field, so a hex scrub or size stepper is one undo
  step) sits beside the M4/M5 node commands. Add/remove/rename never coalesce. `EditorState` exposes a
  small typed API (`setColor`/`addColor`/`renameColor`, and typography/shape/spacing twins); the modal
  `ThemeEditor` dialog is a pure data-driven view over it, so the canvas updates live because the
  document is Compose state.
- **The canvas MaterialTheme is built from the project theme.** `projectColorScheme`/`projectShapes`
  (pure, in the render layer) overlay the project's Material-named `colors.*`/`shapes.*` tokens onto
  the Material defaults. This is why editing `colors.primary` recolours even components that never
  named the token (a Button's container) — the previous wrapper used stock `lightColorScheme()`, so
  theme edits were invisible unless a prop referenced a token. `canvasDark` (transient view state,
  toggled from the toolbar) picks which half of each pair to show.
- **Rename propagation is one command.** `RenameThemeToken` renames the theme map key **and** rewrites
  every `PropValue.ThemeRef` across all screens via the pure, identity-preserving `Node.mapThemeRefs`,
  in a single undoable step. It is a no-op (never merges/clobbers) when the source is absent or the
  target already exists.
- **Theme codegen is a separate method, assembled by the target exporter.** `ThemeEmitter` builds
  `Theme.kt` (a `@Composable AppTheme(darkTheme, content)` wrapper) with KotlinPoet's structural API —
  the code twin of `projectColorScheme`/`projectShapes` (ADR-018), so canvas and compiled output
  agree. It is exposed as `ComposeCodeGenerator.generateTheme(project)` (returns null for an empty
  theme), **not** folded into the `CodeGenerator.generate()` SPI, which stays screens-only so the M6
  golden suite's `generate().single()` contract is untouched. `DesktopExporter` emits `Theme.kt` into
  the Gradle scaffold and wraps `Main` in `AppTheme` when the theme is non-empty — the same place
  `Main.kt` and the wrapper already live (ADR-019). Loose-file export (G4) omits it: a pasted screen
  uses the host project's own theme. Custom (non-Material-named) typography tokens, previously an M6
  TODO, now emit an inline `TextStyle(...)` mirroring the renderer's `resolveTextStyle`; `spacing`
  (not a Material concept) generates as a simple `AppSpacing` object (DATA_MODEL §8).

**Rationale.** Routing theme edits through commands gives undo/redo and live update for free and keeps
the all-mutations-through-commands rule intact. Building the canvas scheme from the theme is what makes H1's "edits apply live
across the canvas" literally true rather than "true only for token-referenced props". Keeping
`generate()` screens-only avoids disturbing every existing golden while still delivering H4 through the
exporter, which is already the home of target-level assembly. Sharing the Material-slot set and color
literal builder between render and codegen (one pure layer) keeps the two from drifting.

**Rejected.** A per-field theme command set (needless surface for a small structure). Folding the theme
file into `generate()` (breaks the `.single()` golden contract and the SPI's screens-only shape for no
gain). A docked theme panel or an inspector tab (competes for the already-tight horizontal space at
1280 px; a modal keeps the four working surfaces intact). Setting `MaterialTheme.typography` on the
canvas wrapper (the per-`Text` `resolveTextStyle` path already themes token-referenced text identically
to the generated output, so a second resolution path would only add drift risk). No schema change was
needed — `Theme` already carries all four maps, so there is no migration.

**Consequences.** Theming is end-to-end and dogfoodable. Adding a themeable modifier/prop picks up the
token picker and codegen for free. The `AppTheme` wrapper's property initializers carry KotlinPoet's
native 2-level continuation indent; normalising that is a future job for the `KotlinFormatter` seam
(ADR-019), not baked into M8. Removing a token leaves any dangling references to fall back gracefully
at render/codegen rather than being rewritten — a deliberate choice, since removal (unlike rename) has
no obvious replacement target.

---

## ADR-021 — Image codegen via the desktop `painterResource(String)` in Phase 1

**Status:** Accepted

**Context.** M9 (Phase 1 complete) adds the `Image` component required by exit criterion #1. Generated
image code must (a) compile with zero manual fixes, and (b) keep the in-process compile gate
(ADR-018, G2/GC-6) self-contained — the gate compiles a single generated `.kt` against the Compose
runtime/foundation/material3/ui classpath, with no Gradle plugins. An `Image`'s `source` is a
`ResourceRef` to a project asset (DATA_MODEL §9), resolved to the asset's project-relative path.

**Decision.** Codegen emits `Image(painter = painterResource("<asset.path>"), contentDescription, …)`
using `androidx.compose.ui.res.painterResource(String)` — the Compose **Desktop** resource API, which
is already on the package's classpath. `contentScale` maps to `ContentScale.<value>` through the same
shared parser (`render/Values.kt`) the canvas uses (the ADR-018 render/codegen marriage). The canvas
resolves the same `ResourceRef` through a `RenderContext.imageLoader` seam that `:app` backs; a missing
asset draws a loud placeholder (ARCHITECTURE §9), never a blank.

**Rationale.** Phase 1 targets Compose Desktop only (see `PROJECT_PLAN.md`). The desktop `painterResource(String)`
is self-contained: the generated file compiles against the existing classpath with no resource-plugin
scaffolding, so the compile gate — the project's highest-value test — stays intact.

**Rejected.** The multiplatform resources API (`org.jetbrains.compose.resources`,
`painterResource(Res.drawable.x)`) is the future-proof, `commonMain`-compatible answer, but it needs
the resources Gradle plugin to generate the `Res` accessor and a `components-resources` dependency; the
plugin cannot run inside kotlin-compile-testing, and `DrawableResource` has an internal constructor, so
a self-contained golden/compile fixture is not achievable. Adopting it now would gut the compile gate.
A placeholder-only Image (no real bitmap) was rejected as not honestly satisfying "images."

**Consequences.** Phase-1 image output compiles and the sample renders on the canvas. **Migration to
`Res.drawable` is a Phase-2 task**, taken up with per-target source-set routing (G9) — it will rewrite
the `Image` emitter and regenerate the image goldens, a localised change beside the renderer.

*Update (M9 follow-up):* the **Gradle export now ships referenced assets**, so an exported project
*runs* unmodified, not merely compiles. `DesktopExporter.gradleProject(project, assetBytes)` takes an
`(Asset) -> ByteArray?` resolver and emits each asset as a `BinaryFile` under `src/main/resources/`
(on the `kotlin("jvm")` runtime classpath, so `painterResource("assets/…")` resolves); `:app` backs
the resolver from the same classpath source the canvas loads from. An earlier symptom — a `LazyColumn`
of `Image` rows rendering blank in an exported app — was this gap: the missing resource made
`painterResource` throw during the list's lazy layout, dropping the whole subtree while the
already-placed title/buttons survived. **Still deferred:** importing asset files from disk into a
project (an "Assets" surface + guarded copy); and loose-file export (G4) stays screens-only, since a
pasted screen has no canonical resources dir — its assets are the host project's responsibility.

---

## ADR-022 — Packaging: vanilla jpackage over Conveyor; per-OS signing in a tagged release workflow

**Status:** Accepted

**Context.** M10 (packaging) must produce installers for at least Windows and Linux, signed, with a
documented install path (PROJECT_PLAN §8; SECURITY §9 DI-1…DI-5). Open question #3 explicitly deferred
"Conveyor vs vanilla Compose packaging" to this milestone. Two things needed pinning: the packaging
toolchain, and how signing/checksums/release-cutting happen given the project's no-network posture
(ADR-011) and CI topology (matrix.yml: cross-OS jobs are GitHub-only because the homelab Forgejo has
no Windows/macOS runners).

**Decision.**
- **Vanilla jpackage via Compose Desktop's `nativeDistributions`.** Configured in `:app`'s own build
  script (not the `viewforge.compose-app` convention plugin — it is metadata for one concrete
  artifact). Target formats are **Windows Msi/Exe and Linux Deb/Rpm**. The Compose Gradle plugin
  unpacks its own WiX, so the Windows MSI builds with no extra toolchain — **verified locally**
  (`ViewForge-0.1.0.msi`). The Linux Deb/Rpm build on a Linux runner (needs `fakeroot`/`rpm`
  installed) and are **not** verifiable on the Windows dev box — the release workflow is their gate.
- **Version is single-sourced** in `gradle.properties` (`viewforge.version`), read config-cache-safely
  via `providers.gradleProperty`, so installer version can't drift.
- **`includeAllModules = true`** rather than jlink module detection. A missing module manifests only
  as a crash in the *installed* app, which no test in this repo can catch; shipping the full runtime
  guarantees the packaged classpath matches the verified `run`/`createDistributable` build. The ~96 MB
  installer size is the accepted cost; trimming via `suggestRuntimeModules` is a post-M10 follow-up.
- **Signing is a per-OS post-build step in a tagged release workflow** (`.github/workflows/release.yml`,
  GitHub-only), never in Gradle: the Compose plugin only wires *macOS* signing. Windows Authenticode
  (`Set-AuthenticodeSignature`) signs the msi/exe; a detached GPG `.asc` signs the Linux deb/rpm. Both
  are **gated on CI secrets** and warn loudly (not fail) when absent, so the pipeline runs before certs
  exist while a real public release must supply them (DI-1). SHA-256 `.sha256` sidecars accompany every
  artifact (DI-2). Artifacts are cut **from the pushed `v*` tag** in CI (DI-3), attached to a draft
  GitHub Release that is published only after all jobs succeed. Releases never trigger on
  `pull_request`, so fork PRs can't reach the signing secrets (DS-7).

**Rationale.** jpackage is already in the Compose Desktop plugin, needs no paid product, and makes **no
network calls in the app** — Conveyor's headline feature (auto-update) is precisely the network channel
ADR-011 excludes, and reversing that would need its own threat-model change. Uploading via the
preinstalled `gh` CLI instead of a third-party release action keeps the supply-chain surface to actions
already pinned elsewhere in the repo (checkout/setup-java/setup-gradle at their existing SHAs).

**Rejected.** **Conveyor** — nicer cross-OS packaging and delta auto-update, but a paid product above a
usage threshold and its auto-update contradicts ADR-011; deferred unless a network decision is
revisited. **Packaging config in the convention plugin** — it is per-artifact metadata, not shared
policy. **macOS (Dmg)** in this milestone — jpackage rejects a major-version 0, so a pre-1.0 mac package
can't carry an honest version, and it needs a Mac runner plus a branded `.icns` to verify; it is a
localised follow-up (add the format + `macOS { }` block + a mac-valid version). **A branded `.ico`/
`.icns` toolchain** — the committed icons are generated placeholders (indigo "VF"); a real icon set is a
follow-up. **jlink module trimming now** — correctness of the installed app over installer size for v1.

**Consequences.** `./gradlew :app:packageMsi`/`packageExe`/`packageDeb`/`packageRpm` produce installers;
a tag push cuts a signed, checksummed GitHub Release. The MSI is verified; Deb/Rpm are exercised only in
CI (honesty: not run on the dev box). The installer is large until modules are trimmed. macOS packaging
and branded icons remain open follow-ups. Any future auto-update remains a documented, network-relevant
decision (ADR-011/DI-4), not a packaging afterthought. Install steps per OS live in `_docs/INSTALL.md`.

---

## ADR-023 — Editor preferences: a dedicated `core/prefs` store; Phase-1 panels are resizable, not dockable

**Status:** Accepted

**Context.** S1 (FEATURES) asks for "dockable/resizable panels" whose "layout persists across sessions."
#39 (PR #42) shipped the menu-backed *visibility* toggles as transient `EditorState` chrome; #43 is the
rest of S1 — resizable panels and cross-session persistence of the layout. Persisting layout forces a
decision the visibility toggles could dodge: **where** does editor chrome live on disk? It must never
enter the `.vforge` document (panel widths are not project data, and would pollute diffs and travel
between machines), yet the project's file-safety rule requires every write to go through the one guarded writer.

**Decision.**
- **A new `core/prefs` module** owns editor-preferences persistence, separate from `core/project`'s
  `.vforge` handling. It defines `EditorPreferences` (a `@Serializable` record with its **own**
  `prefsVersion`, independent of the document `schemaVersion`) carrying a `PanelLayout` — the three
  visibility flags plus three panel widths (plain `Float` dp, so the module stays Compose-free). It
  depends on `core/project` solely to **reuse `GuardedWriter`** — one path-safety implementation, not
  two. `PreferencesStore.save` writes atomically to a `preferences.json` in the platform config dir
  (`%APPDATA%\ViewForge`, `~/Library/Application Support/ViewForge`, or `$XDG_CONFIG_HOME/viewforge`,
  resolved by an injectable `ConfigDir`).
- **Loading is total: it never fails the editor.** A missing, unreadable, or corrupt file yields
  defaults — exactly a fresh install — and out-of-range widths are clamped. This is the deliberate
  counterpart to `ProjectStore`, which reports *why* a load failed because a project *is* the user's
  work; a forgotten panel width is not (ARCHITECTURE §9's "fail loudly for documents" applies to
  documents, not chrome).
- **Phase 1 is resizable + persisted, not dockable.** The fixed palette│tree│canvas│inspector
  arrangement gains drag-to-resize splitters (a dependency-free `ResizableDivider` in `editor/shell`
  using `draggable`, clamped to min/max widths); widths and visibility persist. True drag-to-rearrange
  docking is deferred. This meets S1's acceptance ("layout persists across sessions") without a docking
  framework's state and UX surface.
- **Wiring follows the #37 no-seam precedent.** Persistence has no framework coupling, so nothing goes
  through an SPI-style seam: `:app` loads prefs at startup and applies them to `EditorState` before the
  first frame (no layout flash); a `PreferencesController` in the shell saves at the discrete points
  layout changes (a visibility toggle, the end of a resize drag), so there is no per-pixel write during
  a drag. `EditorState` holds the widths as transient state with clamped `resize*` setters and
  `applyLayout`/`panelLayout` bridges; it does no I/O itself.

**Rationale.** A dedicated module keeps editor-chrome persistence a first-class concern with its own
version, so it never tangles with the `.vforge` migration chain, and gives the later S5 preferences /
window-geometry / recent-files a natural home. Reusing `GuardedWriter` honors rule 6 for free. Total
loading matches the project's "never lose *user work*, but don't nag over chrome" posture. Resizable-only
delivers the persistence S1 actually grades on at a fraction of docking's complexity.

**Rejected.** **Layout in the `.vforge` document** — forbidden: chrome is not project data, would churn
diffs and leak per-machine layout between collaborators. **Folding the store into `core/project`** —
muddies that module's "owns `.vforge` backward-compatibility" identity and couples the two versions.
**The JetBrains Compose `SplitPane` component** — a new pinned dependency and an experimental API for a
need a ~30-line `draggable` divider covers. **Full dockable rearrangement now** — large UX + state
surface for little Phase-1 payoff; a candidate follow-up. **An SPI seam for persistence** — there is no
framework coupling to hide (the #37 precedent), so a seam would be ceremony.

**Consequences.** Panel layout survives restarts, and `core/prefs` is the home for future editor
preferences (S5), window geometry, and recent files — each an additive field with forward tolerance
(`ignoreUnknownKeys`), no document migration. No `.vforge` schema change. Honest gaps: the splitter drag
gesture is not exercised headlessly (the same class of gap as #38's zoom hit-testing), and dockable
rearrangement remains open.

---

## ADR-024 — Reusable components: reference-not-inline, resolved at render and codegen

**Status:** Accepted

**Context.** D7 asks for extracting a selection into a reusable component whose instances "update on
edit," plus enforced cycle detection; P6a asks for user components in the palette. The schema was built
ahead for this: `core/model` already carries `ComponentDef(id, name, parameters, root)`,
`Project.components`, and `ProjectValidator.detectComponentCycles`, and instances were always intended
to be a node of a dedicated type carrying the referenced component id (DATA_MODEL §4). What was
undecided is *how an instance relates to its definition* through the whole pipeline — model, render,
codegen, palette — and where cycle detection bites.

**Decision.** An instance is a thin **reference**, never an inlined copy. A `vforge.userComponent` node
carries the definition's id under a `componentId` literal prop (both constants now canonical in
`model.UserComponent`), and the definition is resolved **at render and at codegen time**, not expanded
into the IR:

- **Codegen** emits each `ComponentDef` as its own `@Composable fun Name(modifier: Modifier = Modifier)`
  file, and an instance emits a *call* — `PrimaryButton(modifier = …)` — passing the instance's own
  modifier chain as the component's `modifier`. One definition, many call sites.
- **Render** threads the component map through `RenderContext` and draws an instance by rendering the
  definition's root inside a `Box` carrying the instance modifier, so the instance is selected and
  instrumented as a single unit while its internals are not.
- **Extract** (`extractComponent`, a `CompositeCommand` of `AddComponent` + `ReplaceNode`) moves the
  selected subtree into a new definition (ids preserved) and swaps in an instance in one undoable step.
- **Cycle policy** stays load-time (PF-3) *and* gains a render-time guard (`RenderContext.expanding`),
  because the canvas renders mid-edit before validation runs. Extraction itself can never form a cycle.
- **Parameters are deferred.** `ComponentDef.parameters` stays `[]`; components are zero-argument
  reusable blocks for now. Adding parameters later is additive (an optional field already in the schema),
  so no bump is owed.

**Rationale.** The reference model is what actually delivers "instances update on edit": because the
instance holds only an id, editing the definition changes every instance for free, in both the canvas
and the generated code — a call site needs no rewrite. Resolving at the edges (render, codegen) keeps the
IR small and framework-agnostic and needs **no `.vforge` schema change, no migration, no fixture**: every
field already exists in schema 1. One composable per component is idiomatic Compose and makes the compile
gate meaningful (the instance call must resolve against the emitted function).

**Rejected.** **Inlining/expanding an instance's subtree into the IR** — breaks update-on-edit (each
instance becomes an independent copy), bloats the document, and duplicates node ids. **A new
`ComponentRenderer`/registry SPI for components** — premature abstraction with one implementation
(ADR-007); threading a component map through the existing `RenderContext`/emitter is enough. **Emitting
component composables into each screen file** — duplicate top-level functions collide when screens are
compiled together. **Parameter inference during extract** — a large sub-feature (which props become
params, instance arg editing in the inspector) with no acceptance pressure yet; deferred behind the
already-present schema field.

**Consequences.** Extract, palette insertion, canvas render, and codegen all agree on one instance shape,
single-sourced by `model.UserComponent`. Adding parameters, a "go to / edit component" surface, and
component rename/delete-with-reference-safety are clean additive follow-ups. Honest gaps: **editing a
component's internals** is not yet a first-class surface — a component is edited only where it is defined,
and the "update on edit" guarantee is proven through the command/codegen/render tests rather than a live
in-place edit gesture; the **extract and palette-drag gestures are not headless-testable** (the same
class of gap as the prior drag/switcher work), so they are covered by the pure command/state tests and
verified by running the app; and **removing an in-use component** is left to the editor to gate rather
than cascade.

---

## ADR-025 — Crash recovery: an atomic config-dir autosave sidecar, cleared only on a clean state

**Status:** Accepted

**Context.** D4 (P0) requires that a crash — or a quit without saving — never loses work: a timer-based
sidecar that, on next launch, offers to restore. The pieces already exist: `ProjectStore`/`ProjectCodec`
serialize a `Project`, `GuardedWriter` writes atomically (temp + rename), and `core/prefs`' `ConfigDir`
resolves the per-user application directory (ADR-023). What was undecided is *where* the recovery lives,
*what* it stores, *when* it is written and cleared, and how it stays from ever blocking startup.

**Decision.** Autosave writes a single **recovery sidecar** to the per-user config directory, separate
from the user's `.vforge` file:

- **Location & format.** One file, `recovery.json`, in `ConfigDir`. It is a `RecoverySnapshot`
  (`recoveryVersion`, `originalPath: String?`, `savedAt`, `document`) — its own versioned format, *not*
  the `.vforge` schema — living in `core/project` beside `ProjectStore` (recovery is user work, not
  chrome). The store takes the directory as a **parameter** (the caller passes `ConfigDir.resolve()`), so
  `core/project` keeps no dependency on `core/prefs`. The config dir — not a path beside the project —
  is the home because a never-saved document has no project directory.
- **When written / cleared.** A shell-owned `RecoveryController` ticks on a timer (a fixed interval in
  Phase 1; a configurable one is S5): while `isDirty` it snapshots the document; once clean (after a real
  Save) it clears the file. It is therefore cleared **only** on a clean state or an explicit discard — so
  a crash *and* a quit-without-saving both leave the snapshot to be offered next launch. No
  `onCloseRequest` hook is needed for safety (a save-prompt-on-close is a separate UX follow-up).
- **Restore.** At launch the controller loads any snapshot; if present, a modal prompt offers Restore
  (swap it in via `EditorState.restoreRecovered`, marked **dirty** — it is ahead of disk) or Discard
  (delete the sidecar). While a recovery is unresolved the timer does nothing, so the freshly loaded
  clean document cannot clear the pending snapshot before the user answers.
- **Loading is total.** A missing, unreadable, or corrupt sidecar loads as `null`, never an exception —
  the same non-fatal contract as `PreferencesStore`, and the deliberate opposite of `ProjectStore`
  (which reports *why* a real document failed). A broken safety net must not stop the app from starting.

**Rationale.** Clearing only on a clean state is what makes the guarantee hold for both a hard crash and
an intentional quit-with-unsaved-edits without needing a reliable shutdown hook (there isn't one for a
crash). Atomic writes mean a crash mid-snapshot cannot corrupt the file. Keeping the sidecar in the
config dir handles never-saved documents uniformly and never risks writing into or beside the user's
project. Reusing `GuardedWriter`/`ProjectCodec` means no new I/O or format machinery, and a
snapshot-format version independent of `schemaVersion` needs **no `.vforge` schema change**.

**Rejected.** **A sidecar beside the project file** (`foo.vforge.autosave`) — leaves never-saved
documents unprotected, clutters the user's directory, and complicates the guarded-writer root. **Clearing
the recovery on window close** — would drop the safety net exactly when a user quits with unsaved work,
and conflates recovery with the (separate) save-prompt-on-close. **Storing recovery in `core/prefs`
`EditorPreferences`** — recovery is the user's work, not chrome, and its load semantics (offer, don't
silently apply) differ; only the *directory* is shared with prefs, not the store. **A configurable
interval now** — S5 scope; a fixed interval ships the P0 without the preferences surface.

**Consequences.** Autosave and restore are proven by pure `RecoveryStore` tests (round-trip,
non-fatal load, clear); the guarantee holds for crash and quit-without-save alike. Honest gaps: the
**timer loop, the restore dialog, and an actual crash are not headless-testable** (verified by running
the app); the **autosave interval is fixed** until S5 (#55); and a **save-prompt on window close**
(#56) remains a separate UX guard layered on top of — not a replacement for — this safety net.

---

## ADR-026 — Device preview frames: a fixed profile registry, per-screen selection as a command, a framed canvas

**Status:** Accepted

**Context.** C6 (P0) asks for selectable viewport profiles (desktop sizes in Phase 1) with the canvas
clipping to the chosen frame. The schema was ready: `Screen.previewProfile: String?` already exists (the
sample carries `desktop_1280x800`), but nothing consumed it — the canvas rendered the root as
`fillMaxSize()` filling the whole viewport, with no device sizing, selector, or profile set. Undecided:
where the profile *definitions* live, whether selecting one is document mutation or view state, and how a
fixed frame coexists with the C5 zoom/pan transform.

**Decision.**
- **A fixed profile registry in `editor/state`.** `DeviceProfile(id, label, width, height)` and a
  `DeviceProfiles` object holding the Phase-1 desktop sizes. Sizes are plain `Float` dp (the module has
  the Compose *runtime* only, not the UI unit types — the panel-width precedent); the canvas attaches
  `.dp`. Ids follow the existing `desktop_<w>x<h>` convention so stored documents resolve. `forId(null
  or unknown)` falls back to a default, so the canvas always has a frame size.
- **Selection is a command.** `previewProfile` is document data (persisted in `.vforge`), so changing it
  goes through `SetPreviewProfile(screenId, profileId?)` (core/command, undoable, mirroring
  `RenameScreen`) — not ad-hoc mutation (rule 3). `profileId` is nullable so the inverse restores a
  never-set screen exactly. It is preview-only: it never affects codegen.
- **A framed canvas.** The inner frame is sized to the active screen's resolved profile
  (`Modifier.size(w.dp, h.dp)`, centered) instead of `fillMaxSize`, so the canvas clips to a real device
  size and a `fillMaxSize` root fills the *device*. The C5 zoom/pan `graphicsLayer` still wraps the
  frame, so a frame larger than the viewport stays navigable (zoom out / pan to see it all). A compact
  toolbar dropdown selects the profile.

**Rationale.** Keeping the definitions in `editor/state` shares them between the toolbar selector and the
canvas without either naming the framework package, and matches how panel widths model dp as `Float`.
Modelling selection as a command keeps it consistent with undo/redo and persistence for free — the frame
choice round-trips through save/load. Sizing the frame *inside* the existing zoom/pan layer means C6
reuses C5's one canonical transform rather than adding coordinate math. **No `.vforge` schema change**:
the field already exists.

**Rejected.** **Profile selection as transient view state (not persisted)** — the schema already stores
it per screen and users expect a screen to remember its frame; a command persists it undoably. **A
profile registry supplied by the framework package (via the catalog SPI)** — device sizes are
framework-agnostic and there is one package until Phase 5 (ADR-007); a plain editor-side list is enough.
**Auto-fitting the zoom to the frame on selection** — nice, but extra logic; C5's manual zoom/pan already
makes a large frame navigable, so fit-to-view is a deferred follow-up rather than P0 scope.

**Consequences.** The canvas now previews at real desktop sizes and the choice persists and undoes.
Adding a profile is a one-line registry entry. Honest gaps: at 100% zoom a profile larger than the
viewport shows clipped until the user zooms out — **auto-fit-to-viewport** is a noted follow-up; and the
**dropdown gesture and the visual frame are not headless-testable** (the command and resolution are
covered by unit tests, the rest by running the app). Phase 2 device profiles (mobile/tablet) slot into
the same registry.

**Amendment (#163) — more presets and a custom size.** The desktop preset list was expanded, and an
arbitrary w×h frame is supported *without* a registry entry or schema change by making the profile id
**self-describing**: `DeviceProfiles.customProfileId(w, h)` produces `custom_<w>x<h>`, and `forId` parses
any `<prefix>_<w>x<h>` id it doesn't recognize as a preset back into clamped dimensions (falling to the
default only for a truly unresolvable string). Because `Screen.previewProfile` already stores just the id
string, a custom size round-trips through save/load/undo for free, per screen. A named preset still wins
over parsing, and parsing an unknown *dimension-encoding* id (e.g. a newer build's `tablet_800x1280`) now
resolves to the real size rather than snapping to the default — a small forward-compat win. The
custom-size entry dialog is shell-owned (material3), and remembering custom sizes as reusable presets is a
noted follow-up (a `core/prefs` concern).

---

## ADR-027 — Root-agnostic node editing: one command family edits screens and components alike

**Status:** Accepted

**Context.** A reusable component (`ComponentDef.root`) is only editable where it was defined; "instances
update on edit" is a tested property, not a live gesture (ADR-024 deferred an in-place editing surface).
Making a component's own tree a first-class editing surface (D7 follow-up, the prerequisite for component
parameters) starts at the command layer: every node command (`AddNode`, `RemoveNode`, `MoveNode`,
`RenameNode`, `SetNodeFlags`, `SetProp`, `SetModifiers`, `SetModifierArg`, `ReplaceNode`) carried a
`screenId: String` and routed through `Project.updateScreenRoot(screenId, …)`, reading invert pre-state
from `doc.screens.firstOrNull{…}.root`. Screens were the only editable root.

**Decision.** Generalize the editing target from *screen* to *any root container* (a screen **or** a
component), keyed by id. Because screen ids and component ids are globally-unique ULIDs, this needs no new
type on the command:
- **New model primitives** (`core/model`): `Project.updateComponentRoot(id, transform)` (the component
  twin of `updateScreenRoot`), `Project.updateRoot(id, transform)` (dispatches to whichever container the
  id names; an unknown id is a no-op), and `Project.findRoot(id): Node?`. All preserve structural sharing
  and return the same instance when nothing changed, exactly like `updateScreenRoot`.
- **Commands point at the primitives.** The shared edit helper becomes `editRoot(rootId)` over
  `updateRoot`, and every invert pre-state lookup becomes `doc.findRoot(rootId)`. The `screenId` field is
  renamed `rootId` on all node commands — an honest name, since it may now hold a component id. Signatures
  stay `String`, so the editor's positional command construction is unaffected.
- **This slice is the primitive only.** No editor wiring: commands *can* target a component, but nothing
  in the editor does yet. The "active edit surface" in `EditorState` (routing canvas/tree/inspector/
  selection to a component root) and the open/return UX are the next slices of the epic.

**Rationale.** Globally-unique ids mean one dispatch (`updateRoot`) covers both containers without a
discriminated `EditTarget` type or retyping every command — the smallest change that unlocks component
editing, and a safe one: screen editing behavior is byte-identical (the full existing command/history
suite, incl. the undo/redo property test, stays green), so the refactor lands with zero user-visible
change and de-risks the slices that follow. Renaming `screenId`→`rootId` avoids the "stale name" trap
(a `screenId` holding a component id) called out in the project's documented anti-patterns. **No `.vforge` schema
change**: screens and components already exist.

**Rejected.** **A discriminated `EditTarget = Screen(id) | Component(id)` on every command** — retypes the
whole command API and all call sites for no gain over a unique-id dispatch. **Keeping `screenId` but
letting it hold component ids** — exactly the stale-naming anti-pattern; the field is now honestly
`rootId`. **Inlining a component for editing** (edit the expanded copy, re-extract on save) — breaks the
reference model (ADR-024) and update-on-edit. **Doing the editor wiring in this slice** — would make one
large, hard-to-review diff; the epic is sliced so the core primitive merges and is proven on its own.

**Consequences.** Any node command now edits a component root by passing its id — the foundation for
edit-a-component-in-place and, after it, component parameters. Next slices add the active-edit-surface
state and the open/return UX. Honest gap: nothing in the editor targets a component yet, so this slice is
proven by command/model unit tests (each command round-trips against a component root; `updateRoot`/
`findRoot` dispatch and no-op correctly), not by a gesture.

---

## ADR-028 — Component parameters: a `ParamRef` prop value, and the schema 1->2 bump it forces

**Status:** Accepted

**Context.** User components are reusable but zero-argument (ADR-024): every instance renders the
definition identically, so a `PrimaryButton` cannot show a different label per instance. Making them
genuinely reusable needs a component to declare typed `parameters` (the field already exists on
`ComponentDef`, additive since schema 1) and each instance to supply argument values. Two of the three
moving parts need no schema change: an instance carries its argument values as ordinary `PropValue`s in
its existing `props` map (keyed by parameter name), and codegen/render consume those. The third does:
a node *inside* the component's `root` has no way to say "this prop's value is my `label` parameter."

**Decision.** Add `PropValue.ParamRef(param: String)` (`@SerialName("param")`) — a reference, by name,
to a parameter of the enclosing component; resolved against the instance's argument props at render and
codegen time, falling back to `Parameter.default`, and never evaluated (PF-4). Because `PropValue` is a
**closed** sealed hierarchy (PF-1), a v1-only build cannot deserialize a `{"kind":"param"}` value, so
this is forward-incompatible and takes the schema to **version 2** (DATA_MODEL §10). The 1->2 migration
(`M1to2`) is data-additive — a v1 document contains no `param` values and is already a structurally
valid v2 document — so it only stamps the version; its real purpose is to mark v2 files so older builds
refuse them cleanly (the `NEWER_SCHEMA` load gate) instead of failing mid-parse. `samples/Demo.vforge`
is pinned at schema 1 as the committed migration fixture; `samples/Gallery.vforge` moves to 2.

**Rationale.** `ParamRef` is a typed, first-class node — the inspector can present it, the canvas can
render it, and codegen emits a bare identifier — matching the "typed values, never bare strings" rule
(ADR-006). Modeling the reference explicitly (rather than reusing an existing variant) keeps render,
codegen, and validation honest about what the value *is*. Scoping this slice to the schema primitive
alone (no codegen/render/inspector) keeps the schema-version change small and reviewable, and lets the
1->2 path land and be proven before behavior is built on it.

**Rejected.** **Reuse `RawExpression(code = paramName)`** — emits the right identifier but marks the
node "unverified" and renders a placeholder (breaks live preview of parameterized components) and is a
semantic lie: a parameter reference is not an arbitrary expression. **Overload `StateBinding(path =
paramName)`** — collides with the reserved Phase-2 data-binding meaning and conflates two concepts.
**Avoid the bump by keeping schema 1** — dishonest: a v1-only build would choke on `{"kind":"param"}`;
the closed hierarchy makes a new member a real forward-incompatibility, exactly what a version bump is
for. **Carry argument values in a bespoke structure rather than the instance's `props`** — unnecessary;
`props` is already a typed `Map<String, PropValue>` and needs no schema change.

**Consequences.** The schema primitive for parameters exists and round-trips (a `ParamRef` survives
encode/decode with its `param` kind); the migration harness gains its first real step. Later slices of
the epic add codegen (typed fn params + call args, a new golden triple), render-time resolution, and
inspector arg editing + extract-lifts-params. Every future `.vforge` this build writes is schema 2.
Honest gap: this slice ships no user-visible parameter behavior — it is proven by model/migration unit
tests (ParamRef round-trip, `M1to2` stamps the version, the committed v1 fixture migrates and loads at
the current version), not by a gesture.

---

## ADR-029 — Safe regeneration: a manifest of owned output, header as a fallback signal

**Status:** Accepted

**Context.** Export (G4 loose files / G5 Gradle project) writes an `ExportFile` bundle through the
`GuardedWriter` with an overwrite *confirmation* (FW-5): the user is asked before any existing file is
replaced, but ViewForge has no idea *which* files in a target directory are its own. G10 asks for a
stronger guarantee — regenerating an entire project into a ViewForge-*owned* directory must replace the
files ViewForge previously generated, remove the ones it no longer emits (a deleted screen's `.kt`), and
**never** touch a file the user authored by hand. That needs a durable record of ownership, since the
generated bundle alone can't distinguish "a stale file we wrote last time" from "a file the user added."

**Decision.** Record ownership in a small `ExportManifest` (`.viewforge/manifest.json`) written into the
managed directory — a versioned, `.vforge`-independent list of the relative paths the last regeneration
emitted. Regeneration (`ProjectExporter.regenerate`) diffs the new bundle against that owned set and the
current tree via the pure `planRegeneration`: a bundle path that exists but is **not owned is blocked**
(the run is refused, writing and deleting nothing); an owned path the new bundle drops and that still
exists is an **orphan to delete**; everything in the bundle is written. As a *fallback* ownership signal
for a text file the manifest doesn't yet list, the G6 generated-source header (`// Generated by
ViewForge …`) counts as owned — so regenerating over a plain earlier export adopts its own `.kt` files
while genuinely hand-authored files (no header) stay unowned. Manifest load is **total** (missing/corrupt
→ nothing owned), which is the safe direction: it makes existing files unowned (refused), never
clobberable. Orphan deletion goes through a new root-confined `GuardedWriter.delete`. G10 is scoped to the
managed **Gradle project** bundle (an owned directory); loose-files export, which pastes into a user's
existing project, keeps its plain confirm-overwrite behavior.

**Rationale.** A manifest is authoritative and covers every file kind (binaries, scaffold, source),
which a header-only scheme cannot (the wrapper jar and `build.gradle.kts` carry no header). Keeping the
diff a pure function over `(bundlePaths, owned, exists)` makes the ownership rules unit-testable without a
filesystem. Refusing wholesale on any unowned collision — rather than skipping the offending file and
writing the rest — avoids leaving a half-regenerated, internally-inconsistent project, and honours "never
lose user work" over convenience. The header fallback is what lets the very first regeneration over an
existing G5 export succeed instead of blocking on its own output.

**Rejected.** **Header-only ownership** — can't mark binaries or scaffold, and a user pasting the header
comment into their own file would fool it; used only as a secondary signal. **Skip-and-continue on an
unowned collision** — produces a project that is neither the user's nor ViewForge's; blocking is safer.
**Delete anything not in the new bundle** — would remove user files in the tree; deletion is confined to
the previously-owned set. **Store the manifest in the `.vforge` document** — ownership is per-output-dir
export metadata, not project data; it lives beside the output, versioned on its own.

**Consequences.** A managed directory gains a `.viewforge/manifest.json`; re-export is idempotent and
self-cleaning within the owned set, and safe against hand-authored files. A regeneration into a directory
with pre-existing, unowned files is refused with the list, so the user can move them or pick another dir.
Honest gap: the confirm/refuse dialogs and the directory picker are GUI-only; the ownership diff, the
write/delete/manifest orchestration, and the header fallback are unit-covered. Empty directories left by
deleted orphans are not pruned (a minor future refinement). No `.vforge` schema change.

---

## ADR-030 — Responsive overrides live on the node as an additive per-breakpoint map

**Status:** Accepted (to be implemented in Phase 2)

**Context.** Phase 2 (Android) needs per-breakpoint property values — a `fontSize`, `padding`, or
`horizontalArrangement` that differs between a phone and a tablet/desktop width. DATA_MODEL §12 left the
placement open with a warning that it must be decided *before* Phase 2, because retrofitting touches
every node. Two shapes were on the table: store overrides **on the node**, or in a **separate override
layer** keyed by node id at the screen level. Whatever we pick has to preserve the invariants the rest
of the system already depends on — ordered modifiers, typed `PropValue`s, stable node identity,
structural sharing on edit, clean diffs, and a `core` that names no framework.

**Decision.** Overrides live **on the `Node`** as one additive optional field:
`responsive: Map<String, Map<String, PropValue>> = emptyMap()` — outer key a **breakpoint id**, inner
map **prop-name → override `PropValue`**. The base `props` map remains the default value (the
smallest/`compact` breakpoint); a breakpoint entry supplies *replacements* for named props, not a full
re-declaration. Breakpoint ids are **opaque strings to `core`**; the set and its thresholds are owned by
the framework package's `TargetDefinition` (the Android target uses Material **window size classes** —
`compact` < 600dp, `medium` 600–840dp, `expanded` ≥ 840dp). Render resolves the active breakpoint's
overrides over the base props before dispatch (the same shape as `bindParameters`, ADR-028/#76); codegen
emits the base value in Phase-2 M13, with window-size-class branching a later slice (M14). Introducing
the field **bumps the schema 2 → 3** with an `M2to3` version stamp and a committed migration fixture
(DATA_MODEL §10), even though the field itself is additive.

**Rationale.** Keeping overrides on the node keeps a node's full state in one place: selection, undo,
copy/paste, extract-to-component, and structural-sharing rebuilds all already operate on a node and its
root-to-node path, so overrides ride along for free with no second structure to keep in sync with node
identity. It also diffs cleanly (the overrides sit next to the props they modify) and needs no new
cross-reference integrity rule. Opaque breakpoint strings keep `core` framework-agnostic — the same
reason `Node.type` and theme tokens are strings — so a future non-Compose package can define its own
breakpoint set. The 2 → 3 bump is deliberately conservative: a v2-only build silently ignoring a
populated `responsive` field would render/emit only base props, a fidelity loss, so this is a *semantic*
change and gets a version bump per DATA_MODEL §10 (the `ParamRef` precedent, ADR-028), with `M2to3` as
the marker that lets an older build hit the `NEWER_SCHEMA` gate cleanly.

**Rejected.** **A separate screen-level override layer keyed by node id** — duplicates node identity into
a parallel structure that every move/delete/reparent/extract must keep consistent, worsens diffs (a
node's responsive behaviour lives far from the node), and breaks the locality that makes structural
sharing cheap. **A distinct `PropValue.Responsive(...)` variant** (like `ParamRef`) — would force
*every* prop reader to understand breakpoints and couples resolution into the value type; a node-level
map resolves once, up front, leaving all existing value emitters/readers untouched. **Making it a purely
additive field with no version bump** — technically allowed by the additive policy, but the silent
data-loss on old builds makes it a semantic change; bumping is the honest, safe call.

**Consequences.** Phase 2 gains responsive layouts without disturbing any existing prop reader — render
and codegen resolve overrides *before* the value pipeline they already have. The schema goes to 3 with a
one-line `M2to3` migration and a fixture; `NodeDisplay`'s exhaustive `PropValue` `when` is unaffected
(the field holds ordinary `PropValue`s). Costs accepted: a third schema version to carry forward, and a
node can now hold prop values that only apply at some widths — the inspector must make the active
breakpoint obvious so an edit's scope is never ambiguous (a Phase-2 UX task, M13).

---

## ADR-031 — Editor chrome stays Material 3, not Jewel

**Status:** Accepted

**Context.** PROJECT_PLAN §3.2 / open question #2 flagged **Jewel** (JetBrains' Compose IDE-chrome
library) as an option for a more IntelliJ-native editor look, to be evaluated before committing.
Jewel's theming and components assume — and in practice require — the **JetBrains Runtime (JBR)** rather
than a stock JDK, which is a real constraint on both the build toolchain and the packaged distribution
(the app ships signed jpackage installers over a pinned JDK 21, ADR-022).

**Decision.** Keep the editor chrome on **plain Material 3**. The shell already runs through its own
`MaterialTheme` with an independent light/dark chrome toggle (S3 — `chromeDark`, distinct from the
project's canvas theme H2). Do not adopt Jewel for v0.1.0-alpha-1, and treat open question #2 as closed.

**Rationale.** The gain from Jewel is cosmetic (IDE-native styling); the cost is a JBR dependency that
would complicate the toolchain and the distribution story just after packaging was settled (ADR-022).
Material 3 is already wired, already themed light/dark, and shares the same widget vocabulary the canvas
and inspector use, so there is no second styling system to maintain. "One implementation before one
abstraction" applies to chrome as much as to the SPI.

**Rejected.** **Adopt Jewel now** — pays a JBR/toolchain cost for a look-and-feel upgrade with no
functional benefit, and right after distribution was finalised. **Abstract the chrome behind a
theming seam so either could be swapped in** — speculative generality for a single app with no second
consumer. Revisiting is cheap if a concrete need appears (the chrome is already funnelled through one
`MaterialTheme`).

**Consequences.** The build stays on a stock JDK 21 and the packaging path (ADR-022) is unchanged. The
editor keeps a Material look rather than an IDE-native one — an accepted trade for a smaller, simpler
toolchain. If Jewel is reconsidered later, it is a contained change at the single `MaterialTheme` seam.

---

## ADR-032 — Re-opening ViewForge-owned output: round-trip of *own output*, not parsing

**Status:** Accepted

**Context.** There is standing user demand (issue #22) to open a `.kt` file in ViewForge and view/edit
it. ADR-003 excludes round-trip parsing of hand-written Compose — a compiler-grade problem — and that
exclusion stands. But a narrower need is real and tractable: re-opening a project ViewForge *itself*
generated. G5 exports a self-contained Gradle project; a user who exported one and lost or moved the
original `.vforge` currently has no way back into the editor, even though every byte of that output came
from a ViewForge IR the tool fully understands. The machinery to recognise our own output already exists
(ADR-029: a `.viewforge/manifest.json` records exactly which paths a regeneration owns, with the
`// Generated by ViewForge` header as a secondary signal).

**Decision.** Support re-opening **only ViewForge-owned output**, and reconstruct the IR from an artifact
ViewForge controls — **never** by parsing Kotlin. The chosen `.kt` is an *entry point*, not a source of
truth:

1. **Ownership gate (reuses ADR-029, read direction).** The `// Generated by ViewForge` header is a weak,
   forgeable *hint*, never authorisation. Authority is the nearest ancestor `.viewforge/manifest.json`
   (loaded *totally* — corrupt ⇒ nothing owned): the `.kt` is ours iff its project-relative path is in
   the manifest's `paths`.
2. **IR sidecar as source of truth.** Export additionally stashes the source `Project` at
   `.viewforge/project.vforge`. Import loads *that* through the existing hardened `ProjectStore.load`
   (strict deserialization, limits, migrations — SECURITY §3), and opens the screen the `.kt` maps to via
   a `path → screen` entry the manifest carries. The reconstruction is therefore lossless (it *is* our IR)
   and involves zero Kotlin reading.
3. **Fail loud, never wrong.** No header, no ancestor manifest, a path the manifest doesn't own, a
   missing/corrupt sidecar, or a newer-than-supported schema each *refuse* with a specific diagnostic. The
   importer never guesses IR from the `.kt` text and never silently opens a blank document.

This is **export metadata, not a schema change**: the sidecar and the `path → screen` map live beside the
manifest under `.viewforge/`, versioned by `ExportManifest.MANIFEST_VERSION`, wholly independent of the
`.vforge` `schemaVersion` (which stays free for #21 to claim v3).

**Rationale.** Re-opening *own output* is not the excluded problem. The excluded problem is deriving IR
from arbitrary Kotlin (control flow, custom composables, computed values with no IR representation);
here the IR already exists and is simply carried alongside the code. Using the `.kt` purely as a
recognised pointer, with the manifest as the ownership authority and the sidecar as the actual source,
keeps the whole thing inside primitives the project already trusts (ADR-029 ownership, the hardened
`.vforge` loader) and adds no parser to maintain in lockstep with codegen.

**Rejected.** **Parse the emitted subset back to IR** — even our own deterministic KotlinPoet output needs
a real tokeniser (strings, nested lambdas, modifier chains), is fragile, must track every codegen change,
and brushes the spirit of ADR-003; the sidecar is lossless for a fraction of the code and risk.
**The `// region ViewForge … // endregion` regenerated block** (the mitigation floated in ADR-003 /
PROJECT_PLAN §7.1 and the original #22 note) — still requires reading Kotlin around the fence and solves a
different problem (embedding in a hand-written file); superseded here for the re-open use case.
**General round-trip parsing** — remains excluded (ADR-003). **Store the sidecar/ownership in the
`.vforge`** — it is per-output-dir export metadata, not project data (same reasoning as ADR-029's
manifest).

**Consequences.** A managed export directory gains a `.viewforge/project.vforge` beside its
`manifest.json`, and re-opening a generated `.kt` becomes possible whenever that owned `.viewforge/` tree
travels with it. A lone `.kt` copied away from its `.viewforge/` cannot be opened — an honest, fail-loud
boundary rather than a guess. ADR-003's "Consequences" now cross-references this narrow carve-out. No
`.vforge` schema change. Reading the `.kt` and walking to `.viewforge/` is untrusted-input handling and is
covered by SECURITY §3 (PF-9/PF-10).

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

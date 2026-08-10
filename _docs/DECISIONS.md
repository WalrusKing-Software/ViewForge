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

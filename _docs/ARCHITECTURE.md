# ViewForge — Architecture

**Status:** Living — Phase 1 (Compose Desktop) shipping as **v0.1.0-alpha-1**. Phase 2 (Android) planned; see
[`PROJECT_PLAN.md`](PROJECT_PLAN.md) §2.

---

## 1. Guiding principles

1. **The IR is the source of truth.** The canvas renders it; codegen emits it; the file stores it.
   Nothing else is authoritative.
2. **The editor core knows nothing about Compose.** Framework knowledge lives in a package behind an
   SPI. This is enforced by build configuration, not good intentions.
3. **Render with the real framework.** The canvas runs actual Compose composables. Never
   reimplement layout.
4. **All mutations go through commands.** Undo/redo and future collaboration depend on it.
5. **Generated code is a product, not exhaust.** It must be readable enough to hand to a colleague.
6. **One implementation before one abstraction.** The SPI exists from day one so the boundary is
   visible; it will be *revised* against the second framework, not frozen now.

---

## 2. High-level shape

```
                         ┌──────────────────────────────┐
                         │        Editor Shell          │
                         │  window · menus · dialogs    │
                         └──────────────┬───────────────┘
                                        │
        ┌───────────────┬───────────────┼───────────────┬────────────────┐
        │               │               │               │                │
   ┌────▼────┐    ┌─────▼─────┐   ┌─────▼─────┐   ┌─────▼─────┐   ┌──────▼──────┐
   │ Palette │    │ Tree Panel│   │  Canvas   │   │ Inspector │   │ Code Panel  │
   └────┬────┘    └─────┬─────┘   └─────┬─────┘   └─────┬─────┘   └──────┬──────┘
        │               │               │               │                │
        └───────────────┴───────┬───────┴───────────────┘                │
                                │                                        │
                        ┌───────▼────────┐                               │
                        │  EditorState   │◄──────────────────────────────┘
                        │ document·sel·  │
                        │ history        │
                        └───────┬────────┘
                                │ commands
                        ┌───────▼────────┐
                        │   Document     │   ← the IR (core/model)
                        └───────┬────────┘
                                │
                ┌───────────────┴────────────────┐
                │        Framework Package        │  (core/spi implementation)
                │  ┌──────────┐  ┌────────────┐  │
                │  │ Renderer │  │  CodeGen   │  │
                │  └──────────┘  └────────────┘  │
                │  ┌──────────────────────────┐  │
                │  │ Component & Modifier      │  │
                │  │ Definitions (schema)      │  │
                │  └──────────────────────────┘  │
                └────────────────────────────────┘
```

---

## 3. Module responsibilities

### `core/model`
Pure Kotlin data classes for the IR. **Zero dependencies** beyond stdlib and
`kotlinx.serialization`. No Compose, no UI, no I/O. This module must be trivially testable and
should compile for any KMP target.

### `core/project`
`.vforge` reading/writing, schema versioning, migration chain, validation. Owns backward
compatibility.

### `core/prefs`
Per-user **editor preferences** — chrome, not document data: panel layout (visibility + widths)
today; window size, recent files, and the S5 preferences later. Persisted to a config file in the
platform config dir, written through the same guarded writer as `core/project`, and kept strictly
separate from the `.vforge` document (its own `prefsVersion`). Compose-free. See ADR-023.

### `core/command`
Command pattern implementation. Every mutation is a `Command` with `apply`/`invert`. Owns the undo
and redo stacks, transaction grouping (e.g. a drag = one undoable command), and history limits.

### `core/spi`
The interfaces a framework package implements. Nothing here mentions Compose. See §6.

### `editor/*`
The Compose Desktop application UI. Depends on `core` and loads packages at runtime through the SPI.
Should not import from `packages/compose` directly — only through SPI types. (Exception during
Phase 1: a compile-time dependency for bootstrapping is acceptable if isolated to a single wiring
module, but the coupling must be one clearly-marked file.)

### `packages/compose`
Everything Compose-specific: component definitions, runtime renderers, modifier handling, KotlinPoet
emitters, and per-target exporters.

### `app`
Entry point, dependency wiring, packaging configuration.

---

## 4. The rendering model (the core technical decision)

### 4.1 Interpreted, not compiled

The canvas **interprets the IR at runtime** and calls real composables. It does **not** compile
generated Kotlin to preview it.

Why: compiling on every keystroke is far too slow for a WYSIWYG loop (Gradle invocation is
seconds-to-minutes; the editing loop needs sub-frame updates). Interpreted rendering also removes
the need to bundle or locate a Kotlin compiler and Gradle, which keeps the app small and the
"packages" story simple.

**The accepted cost:** the canvas is a *faithful re-composition* of the same widgets, not literally
the generated code executing. Canvas and output can drift. This is why **screenshot-diff testing
between canvas and compiled output is a Phase 1 exit criterion, not a nice-to-have.**

### 4.2 The renderer walk

```kotlin
@Composable
fun RenderNode(node: Node, ctx: RenderContext) {
    val definition = ctx.registry.componentFor(node.type)
    val modifier = ctx.buildModifier(node.modifiers)     // ordered fold
        .then(ctx.editorInstrumentation(node.id))         // bounds capture + hit target
    definition.Render(node, modifier, ctx) { childNodes ->
        childNodes.forEach { RenderNode(it, ctx) }
    }
}
```

Each component definition owns its own `@Composable Render` function. Adding a component means
adding a definition, not touching the canvas.

### 4.3 Modifier chain construction

Compose's `Modifier` is an **ordered, non-commutative** chain — `padding().background()` and
`background().padding()` produce different results. The IR therefore stores modifiers as an ordered
list, and the renderer folds them in order:

```kotlin
fun buildModifier(entries: List<ModifierEntry>): Modifier =
    entries.fold(Modifier as Modifier) { acc, e ->
        registry.modifierFor(e.type).apply(acc, e.args)
    }
```

The inspector UI **must** expose and allow reordering of this list. Hiding the ordering would make
whole classes of layouts unreachable and would make the canvas lie about the output.

### 4.4 Selection and hit-testing

Approach: **editor instrumentation injected into the modifier chain**, not the semantics tree.

Each rendered node gets `Modifier.onGloballyPositioned { }` appended, writing its
`LayoutCoordinates` into a per-frame spatial index keyed by node ID. A transparent overlay above the
canvas receives pointer input and resolves a click to the **deepest node whose bounds contain the
point**, walking the index.

Why not the semantics tree: semantics is designed for accessibility and testing, it does not map
1:1 to IR nodes (some composables emit no semantics, others merge), and it would couple editor
internals to a framework-specific concept that other framework packages won't share.

The overlay also draws selection outlines, hover highlights, drop indicators, and resize handles —
keeping all editor chrome out of the rendered user UI so it can never affect layout.

**Known hard case:** zero-size nodes (an empty `Column`, a `Spacer` with no size) are unselectable
by geometry. Mitigation: the tree panel is always a complete alternative selection surface, and the
canvas renders a minimum-size affordance for empty containers in edit mode only.

---

## 5. State management

Single-source-of-truth, unidirectional:

```
User gesture → Command → EditorState.execute(cmd) → new Document → recomposition
```

- `Document` (the IR root) is **immutable**. Commands return new instances.
- `EditorState` holds `document`, `selection`, `history`, `viewport`, and transient UI state, exposed
  as Compose state so the canvas recomposes automatically.
- **Structural sharing matters.** Naive deep-copying of the whole tree per keystroke will get slow
  on large screens. Copy only the path from root to the changed node; siblings stay referentially
  identical, which also lets Compose skip recomposing them.

### Undo/redo

- Every mutation is a `Command` with `apply(doc): Document` and `invert(doc): Command`.
- Continuous gestures (drag, slider scrub) open a **transaction** that coalesces into one history
  entry on release.
- History is capped (configurable; default ~200 entries) and cleared on document close.
- Property-based tests: for any random command sequence, `undo` × N then `redo` × N must return the
  document to an equal state.

---

## 6. The framework package system

### 6.1 Design intent

A **framework package** teaches ViewForge about one UI framework. Compose is the first (and for a
long while, only) one. Defining the SPI now keeps the boundary visible; it will be revised once a
second framework exists.

### 6.2 The SPI (`core/spi`)

```kotlin
interface FrameworkPackage {
    val id: PackageId                    // e.g. "compose-multiplatform"
    val displayName: String
    val version: SemVer
    val apiVersion: Int                  // SPI compatibility

    val components: List<ComponentDefinition>
    val modifiers: List<ModifierDefinition>
    val targets: List<TargetDefinition>   // desktop, android, ios, web
    val themeSchema: ThemeSchema
}

interface ComponentDefinition {
    val type: String                     // "compose.material3.Button"
    val category: String                 // palette grouping
    val props: List<PropDefinition>      // typed schema drives the inspector
    val slots: List<SlotDefinition>      // named child regions
    val acceptsChildren: Boolean
}

interface ComponentRenderer<N> {         // framework-specific rendering half
    fun render(node: Node, ctx: RenderContext)
}

interface CodeGenerator {
    fun generate(doc: Document, target: TargetDefinition): List<GeneratedFile>
}

interface TargetDefinition {
    val id: String                       // "desktop" | "android" | "ios" | "web"
    val previewProfiles: List<PreviewProfile>   // viewport, density, insets
    fun sourceSetFor(file: GeneratedFile): String  // commonMain, androidMain, ...
}
```

**Status (M11, #218):** `TargetDefinition` is real in `core/spi` with `id` + `sourceSetFor` — the
Compose package supplies `DesktopTarget`/`AndroidTarget` (`packages/compose/targets`), and the
`MultiplatformExporter` routes each generated file through `sourceSetFor` to place shared screens in
`commonMain` and each platform's entry point (`Main.kt` → `jvmMain`, `MainActivity.kt` + manifest →
`androidMain`), so **G9 source-set-aware export** has landed.

**Status (M12, #220):** `previewProfiles` has landed. Each target now contributes its canvas device
frames — `DesktopTarget` the window sizes, `AndroidTarget` real device frames with density and system-bar
insets (`PreviewProfile`/`PreviewInsets`, framework-neutral `core/spi` data). The app aggregates every
target's list and injects it into `EditorState`, so the editor still names no framework; the canvas draws
the inset chrome (ADR-026 Phase-2 amendment). This is a real consumer, so the profiles are target-owned
now and not ahead of one (ADR-007), mirroring ADR-037's target-owned breakpoint thresholds.

**Important:** `ComponentDefinition` (schema — pure data) is separate from `ComponentRenderer`
(rendering — Compose-typed). The schema half can live in a `core`-compatible module; only the
renderer half needs the framework. This split is what makes the inspector, palette, validation, and
codegen framework-agnostic.

### 6.3 Loading

- Phase 1: the Compose package is **statically linked**. No dynamic loading. Simplest thing that
  works, and it lets the SPI change freely while it's still wrong.
- Phase 5: dynamic loading via JAR + `ServiceLoader` with an isolated `URLClassLoader` per package.

**Dynamic loading is a security decision, not just a technical one.** A package is arbitrary
executable code with rendering and file-writing capability. Do not ship dynamic third-party loading
without reading [`SECURITY.md`](SECURITY.md) §4 and implementing what it requires.

### 6.4 Multi-target ≠ multi-package

Compose Multiplatform is **one package with four target exporters**, mirroring KMP's own source-set
model (`commonMain`, `androidMain`, `iosMain`, `jvmMain`, `wasmJsMain`). A package owns a shared
component model plus N targets. Don't conflate "package" with "platform" — the distinction matters
later when, e.g., a React package needs both web and React Native targets.

---

## 7. Code generation pipeline

```
Document ──► validate ──► per-target lowering ──► KotlinPoet build ──► format ──► write
```

1. **Validate.** Reject or warn on unknown types, invalid prop values, unresolved theme refs.
   Codegen should never be the first place an error surfaces.
2. **Lower.** Target-specific adjustments (e.g. an Android-only modifier, a web fallback).
3. **Build.** Emit `FileSpec`/`FunSpec` via KotlinPoet — a real syntax model, not string
   concatenation. Imports are managed by KotlinPoet, which eliminates a whole class of bugs.
4. **Format.** Run ktlint/spotless rules over output. KotlinPoet 2.x changed wrapping behavior
   (spaces no longer auto-wrap; `♢` marks wrappable spaces), so formatting needs explicit attention.
5. **Write.** Through a guarded writer that validates destination paths (see `SECURITY.md` §5).

### Output shape

Each screen becomes a `@Composable` function in its own file. Reusable components become their own
composables. Theme becomes a `MaterialTheme` wrapper with extracted color/typography/shape values.

**Generated files carry a header** marking them as generated, naming the source `.vforge` file and
the schema version, so anyone encountering the file knows where it came from and that hand-edits may
be overwritten.

---

## 8. Threading

- **All IR mutation happens on the main/UI thread.** The IR is immutable and small; locking it would
  add complexity for no measurable gain.
- **Codegen and file I/O run off-thread** (`Dispatchers.IO`) against an immutable document snapshot,
  so a long export never blocks the canvas.
- **Asset loading (images) is async** with a cache keyed by path + mtime.

---

## 9. Error handling philosophy

The editor must **never lose user work.** Concretely:

- Renderer exceptions are caught per-node; a failed node draws an error placeholder rather than
  crashing the canvas.
- Codegen failures are reported with the offending node ID and are recoverable.
- Autosave to a sidecar file on a timer and before risky operations (export, package load).
- Project load failures fall back to reporting *what* failed to parse rather than discarding the
  file.

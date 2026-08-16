# ViewForge — Data Model

**Status:** Living — shipping in **v0.1.0-alpha-1** (Phase 1).
**Schema version:** 2

This document defines the intermediate representation (IR) and the `.vforge` project file format.
Everything else in the system is downstream of this, so changes here are expensive — treat this
document as the contract.

---

## 1. Design requirements

| Requirement | Why |
|---|---|
| Framework-agnostic core shape | Phase 5 requires the same tree to describe non-Compose UIs. |
| Ordered modifiers | Compose's `Modifier` chain is non-commutative; order is semantic. |
| Typed prop values, not strings | The inspector, validation, and codegen all need types. Strings force re-parsing everywhere. |
| Stable node IDs | Selection, undo, diffing, and future collaboration all key on identity. |
| Versioned + migratable | Files outlive schema versions. |
| Diff-friendly | Users will commit `.vforge` to git. Stable key ordering, no volatile fields. |
| Forward-tolerant | Unknown fields preserved where possible rather than silently dropped. |

---

## 2. Top-level: the project file

`.vforge` is JSON (via `kotlinx.serialization`). Pretty-printed with stable key order for clean
diffs.

```jsonc
{
  "schemaVersion": 1,
  "id": "01J8X...",                    // ULID, stable for the project's life
  "name": "My App",
  "createdAt": "2026-08-09T12:00:00Z",
  "framework": {
    "packageId": "compose-multiplatform",
    "packageVersion": "1.0.0"
  },
  "targets": ["desktop"],              // enabled export targets
  "theme": { /* §6 */ },
  "screens": [ /* §3 */ ],
  "components": [ /* §4 */ ],
  "assets": [ /* §7 */ ]
}
```

### Notes

- `framework.packageVersion` records what produced the file. On load, mismatches produce a warning,
  not a hard failure.
- `targets` is a list from day one even though Phase 1 has only `desktop` — adding it later would be
  a migration.
- **No absolute paths anywhere.** Asset references are project-relative so the file is portable and
  doesn't leak the author's directory structure into a committed file.

---

## 3. Screen

A top-level, exportable UI entry point.

```jsonc
{
  "id": "scr_01J8X...",
  "name": "HomeScreen",               // becomes the composable function name
  "root": { /* Node, §5 */ },
  "previewProfile": "desktop_1280x800"
}
```

`name` must be a valid Kotlin identifier after normalization. Validate at edit time, not at codegen.

---

## 4. Reusable component

A user-defined composable, reusable across screens.

```jsonc
{
  "id": "cmp_01J8X...",
  "name": "PrimaryButton",
  "parameters": [
    { "name": "label", "type": "String", "default": { "kind": "literal", "value": "Click" } },
    { "name": "onClick", "type": "Function0<Unit>", "default": null }
  ],
  "root": { /* Node */ }
}
```

Instances reference these via a node whose `type` is `"vforge.userComponent"` and whose props carry
the referenced component ID under the `componentId` key (a `literal` string) plus argument values.
These two conventions are pinned as constants on `model.UserComponent` (`TYPE` / `COMPONENT_ID_PROP`)
and shared by the validator, renderer, and generator so there is one source of the contract.

An instance is a **reference, resolved at render and codegen time — never inlined into the IR**
(ADR-024). This is what makes instances "update on edit": codegen emits each component as its own
`@Composable fun` and an instance as a *call* to it; the canvas renders the definition's tree in the
instance's place. Editing a definition therefore updates every instance without touching the instances.
**Parameters (schema 2, ADR-028).** A node inside the component's `root` references a parameter with
`PropValue.ParamRef(param)` (see §6). An *instance* supplies argument values as ordinary `PropValue`s
in its own `props` map, keyed by parameter name (alongside the reserved `componentId` key) — the
instance-`props` side needs no schema change, only `ParamRef` does. Codegen emits each component as a
`@Composable fun` with typed parameters and each instance as a call passing the argument values;
render resolves each `ParamRef` against the instance's args, falling back to `Parameter.default`.
*(Codegen — typed fn params + call args — ships in slice 2; render-time resolution and inspector arg
editing land in later slices of the parameters epic.)*

**Cycle detection is required.** A user component must not, directly or transitively, contain
itself. It is validated on load (`ProjectValidator`) and guarded again at render time
(`RenderContext.expanding`), because the canvas renders mid-edit before load validation runs.

---

## 5. Node — the central type

```kotlin
@Serializable
data class Node(
    val id: NodeId,                              // ULID; stable across edits
    val type: String,                            // "compose.foundation.layout.Column"
    val name: String? = null,                    // optional user label for the tree panel
    val props: Map<String, PropValue> = emptyMap(),
    val modifiers: List<ModifierEntry> = emptyList(),   // ORDERED — semantic
    val children: List<Node> = emptyList(),
    val slots: Map<String, List<Node>> = emptyMap(),
    val locked: Boolean = false,                 // editor-only: protects this node (per-node, not its subtree)
    val hidden: Boolean = false                  // editor-only: excluded from render AND codegen
)
```

### Field notes

**`id`** — ULID rather than UUID: sortable, compact, and time-ordered, which makes diffs and debug
logs more readable. Never reused, never reassigned. Copy/paste generates fresh IDs.

**`type`** — a fully-qualified string namespaced by package. Format:
`<framework>.<library>.<Component>`. Strings, not enums, because a package registry supplies them at
runtime and `core` must not know the set.

**`children` vs `slots`** — `children` is the default content region. `slots` handles components with
multiple named regions (a `Scaffold` has `topBar`, `bottomBar`, `floatingActionButton`, `content`).
Keeping them separate avoids encoding slot identity into child ordering, which would be fragile.

**`hidden`** — deliberately excludes the node from *both* render and codegen. A "visible in editor
but not in output" state would be a fidelity lie.

**`locked`** — editor-only protection (T4), scoped **per-node, not to the subtree**: a locked node is
non-selectable (canvas click, marquee, tree click), non-draggable, cannot receive dropped children (a
container *nested inside* it still can — its own child list changes, not the locked node's), and cannot
be renamed; it is also skipped by tree keyboard navigation. Because selection is the gateway to prop,
modifier, delete, duplicate, cut, and copy operations, a locked node is protected from those too. It
has **no effect on render or codegen** — a locked node is emitted exactly like an unlocked one. The
canvas draws a padlock badge and a faint outline on locked nodes, and the tree marks locked rows, so
the protection is visible rather than looking inert.

---

## 6. PropValue — the typed union

Props are **never bare strings.** A sealed hierarchy, serialized with a `kind` discriminator:

```kotlin
@Serializable
sealed interface PropValue {
    @Serializable @SerialName("literal")
    data class Literal(val value: JsonPrimitive) : PropValue

    @Serializable @SerialName("theme")
    data class ThemeRef(val token: String) : PropValue        // "colors.primary"

    @Serializable @SerialName("resource")
    data class ResourceRef(val assetId: String) : PropValue

    @Serializable @SerialName("expression")
    data class RawExpression(val code: String) : PropValue    // ESCAPE HATCH — see below

    @Serializable @SerialName("binding")
    data class StateBinding(val path: String) : PropValue     // RESERVED, Phase 2+

    @Serializable @SerialName("param")
    data class ParamRef(val param: String) : PropValue        // component parameter, §4 / ADR-028
}
```

### `ParamRef` — component parameters (schema 2)

Only meaningful inside a `ComponentDef.root`: it names one of the component's `parameters`. At render
and codegen time it resolves against the argument value the *instance* supplies (falling back to the
parameter's `default`); it is never evaluated. Adding it is what took the schema to **version 2** — a
new member of the *closed* `PropValue` hierarchy cannot be deserialized by a v1-only build, so unlike
an additive optional field it is forward-incompatible and needs a version bump (§10, ADR-028). The
1→2 migration only stamps the version (`M1to2`).

### `RawExpression` — the escape hatch

Passes a literal Kotlin expression straight through to codegen. This is **necessary** (no allowlist
will ever cover everything) and **dangerous** (unvalidatable, may not compile, may not render).

Rules:
- The canvas cannot evaluate it — render a visible placeholder, never a silent wrong result.
- The UI must mark any node using one as "unverified."
- Codegen emits it verbatim. It is the user's responsibility.
- Document it as an advanced feature; never generate one automatically.

### `PropDefinition` (schema side)

Supplied by the framework package; drives the inspector UI and validation:

```kotlin
data class PropDefinition(
    val name: String,
    val type: PropType,        // String|Int|Float|Bool|Color|Dp|Enum|Alignment|...
    val required: Boolean,
    val default: PropValue?,
    val enumValues: List<String>? = null,
    val range: ClosedFloatingPointRange<Float>? = null,
    val description: String? = null
)
```

`PropType` determines the editor control: `Color` → color picker, `Dp` → numeric stepper with unit,
`Enum` → dropdown. The inspector is fully data-driven — **adding a component must never require
writing inspector UI.**

---

## 7. ModifierEntry

```kotlin
@Serializable
data class ModifierEntry(
    val id: String,                       // stable, for reorder/undo
    val type: String,                     // "compose.padding"
    val args: Map<String, PropValue> = emptyMap(),
    val enabled: Boolean = true           // toggle off without deleting
)
```

**Order is data.** The list index is semantic and must be preserved through every serialization,
copy, and undo operation. The inspector must support drag-reordering.

`enabled: false` skips the modifier in both render and codegen — useful for experimentation without
losing configuration.

### Phase 1 modifier allowlist

`padding` · `size` / `width` / `height` / `fillMaxWidth` / `fillMaxHeight` / `fillMaxSize` ·
`background` · `border` · `clip` · `alpha` · `weight` · `align` · `clickable` · `offset` ·
`aspectRatio` · `shadow` · `rotate` · `scale`

Anything else goes through `RawExpression` until promoted to a first-class definition.

---

## 8. Theme

```jsonc
{
  "colors": {
    "primary":   { "light": "#6750A4", "dark": "#D0BCFF" },
    "onPrimary": { "light": "#FFFFFF", "dark": "#381E72" }
  },
  "typography": {
    "titleLarge": { "fontFamily": "default", "fontSize": 22, "fontWeight": 400, "lineHeight": 28 }
  },
  "shapes": { "small": 4, "medium": 8, "large": 16 },
  "spacing": { "xs": 4, "sm": 8, "md": 16, "lg": 24, "xl": 32 }
}
```

- Light/dark pairs from the start — retrofitting dark mode into a single-value schema is a migration.
- `spacing` is not a Material concept but is universally useful; it generates as a simple object.
- Theme tokens are referenced by `PropValue.ThemeRef`, so a token rename can be propagated
  automatically. **This is the main reason to use tokens over literal colors, and the UI should
  nudge toward it.**

---

## 9. Assets

```jsonc
{
  "id": "ast_01J8X...",
  "type": "image",
  "path": "assets/logo.png",     // ALWAYS project-relative
  "originalName": "logo.png",
  "width": 512,
  "height": 512
}
```

Assets are copied into the project directory on import, never referenced in place. This keeps
projects portable and avoids broken references when files move.

---

## 10. Versioning and migration

```kotlin
interface Migration {
    val fromVersion: Int
    val toVersion: Int
    fun migrate(json: JsonObject): JsonObject
}
```

Rules:
1. `schemaVersion` increments on **any** breaking change.
2. Migrations are chained (1→2→3), never skipped.
3. Every migration has a test with a real fixture file committed to `samples/`.
4. Migrations operate on `JsonObject`, **not** deserialized classes — otherwise migrating requires
   keeping every historical version of every data class alive forever.
5. Loading a **newer** schema than the app supports fails with a clear message, never a partial load.
6. Back up the original file before writing a migrated version.

### Additive-change policy

Adding an optional field with a default does **not** require a version bump — `kotlinx.serialization`
handles missing fields via defaults. Reserve version bumps for renames, removals, and semantic
changes.

---

## 11. Worked example

A screen with a centered column containing a title and a button:

```jsonc
{
  "schemaVersion": 1,
  "id": "01J8XABCDEF",
  "name": "Demo",
  "framework": { "packageId": "compose-multiplatform", "packageVersion": "1.0.0" },
  "targets": ["desktop"],
  "theme": { "colors": { "primary": { "light": "#6750A4", "dark": "#D0BCFF" } } },
  "screens": [{
    "id": "scr_01",
    "name": "HomeScreen",
    "previewProfile": "desktop_1280x800",
    "root": {
      "id": "n_01",
      "type": "compose.foundation.layout.Column",
      "props": {
        "horizontalAlignment": { "kind": "literal", "value": "CenterHorizontally" },
        "verticalArrangement":  { "kind": "literal", "value": "Center" }
      },
      "modifiers": [
        { "id": "m_01", "type": "compose.fillMaxSize", "args": {} },
        { "id": "m_02", "type": "compose.padding",
          "args": { "all": { "kind": "literal", "value": 24 } } }
      ],
      "children": [
        {
          "id": "n_02",
          "type": "compose.material3.Text",
          "props": {
            "text":  { "kind": "literal", "value": "Welcome" },
            "style": { "kind": "theme",   "token": "typography.titleLarge" },
            "color": { "kind": "theme",   "token": "colors.primary" }
          }
        },
        {
          "id": "n_03",
          "type": "compose.material3.Button",
          "props": { "onClick": { "kind": "expression", "code": "{ /* TODO */ }" } },
          "modifiers": [
            { "id": "m_03", "type": "compose.padding",
              "args": { "top": { "kind": "literal", "value": 16 } } }
          ],
          "slots": {
            "content": [{
              "id": "n_04",
              "type": "compose.material3.Text",
              "props": { "text": { "kind": "literal", "value": "Get started" } }
            }]
          }
        }
      ]
    }
  }],
  "components": [],
  "assets": []
}
```

### Expected generated output

This is the actual M6 output (`packages/compose/src/test/resources/golden/Demo.kt`). Two things worth
noting versus a hand-written version: KotlinPoet manages the import block and emits an explicit
`public` (it has no toggle; the G7 formatting pass at export can strip it), and argument order mirrors
each renderer's Composable call (ADR-018) — e.g. `Column`'s `verticalArrangement` before
`horizontalAlignment`, matching `render/Components.kt`.

```kotlin
// Generated by ViewForge — do not edit.
// Source: Demo.vforge (schema 1)
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
public fun HomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Welcome",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleLarge,
        )
        Button(
            onClick = { /* TODO */ },
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(text = "Get started")
        }
    }
}
```

**This example is the first golden-file test** (`Demo.vforge` → `Demo.kt`), asserted exactly by
`GoldenCodegenTest` and compiled by `CompilationTest`.

---

## 12. Open modeling questions

1. **Root modifier parameter.** *Resolved (M6, ADR-018).* Generated composables take
   `modifier: Modifier = Modifier`; the root node chains its own modifiers onto that parameter,
   caller's first — `modifier.fillMaxSize().padding(24.dp)` — matching Compose convention.
2. **Lists and repeaters.** Static children only in Phase 1. A `LazyColumn` bound to a data source
   needs a repeater concept — real design work, deferred.
3. **Responsive variants.** *Resolved (ADR-030), to be implemented in Phase 2.* Per-breakpoint prop
   overrides live **on the node** as an additive optional field
   `responsive: Map<String, Map<String, PropValue>>` (breakpoint-id → prop-name → override value); the
   base `props` map is the default (the smallest/compact breakpoint). Breakpoint identities are opaque
   strings to `core` and defined by the framework package's target (Material window size classes —
   `compact`/`medium`/`expanded` — for the Android target), so `core` stays framework-agnostic.
   Introducing the field will bump the schema **2 → 3** (an `M2to3` stamp) because it is a semantic
   capability old builds would silently drop, following the `ParamRef` precedent (ADR-028). A separate
   node-id-keyed override layer was rejected — see ADR-030.
4. **Interaction/navigation.** Out of scope for v1, but reserve `StateBinding` so it can arrive
   without a schema break.

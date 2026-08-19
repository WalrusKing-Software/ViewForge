# ViewForge — Data Model

**Status:** Living — shipping in **v0.1.0-alpha-1** (Phase 1).
**Schema version:** 6 (v3 = read-only data binding, ADR-034; v4 = nested lists, ADR-034 Amendment #255; v5 = component-local state, ADR-034 Amendment #266; v6 = interactive state & events, ADR-035; see §10 version history)

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
  "previewProfile": "desktop_1280x800",
  "state": [ /* StateField, read-only data binding — see below (schema 3, ADR-034) */ ]
}
```

`name` must be a valid Kotlin identifier after normalization. Validate at edit time, not at codegen.

### `state` — read-only screen state (schema 3, ADR-034)

`state: List<StateField>` (additive, default empty) declares the **read-only** data a screen's props bind
to (#21). Each `StateField` is `(name, type, sample)`:

- **`name`** — a legal Kotlin identifier (GC-3), the binding root a `PropValue.StateBinding` path names (§6).
- **`type`** — either a **scalar** (`String`/`Int`/`Float`/`Bool`) or a **list of records**. A record's field
  carries a full type in turn, so a field may itself be a **nested list of records** — the model is *recursive*
  (nested lists, ADR-034 Amendment #255). These cover #21's examples (a live indicator binds a scalar; a dynamic
  list / populated dropdown repeats over a list; a list of sections each holding a list of rows nests).
- **`sample`** — a typed literal value (the same `JsonPrimitive`/structured-literal trust boundary as any
  `PropValue.Literal`), **never code**: a scalar literal, or rows for a list — and a row's cell is itself a
  sample value, so a nested-list cell holds its own rows (recursive, mirroring `type`). The canvas renders the
  sample and codegen seeds the generated stub from it (ADR-034).

A `vforge.repeat` node iterates a list field (§12.2); a scalar field feeds a scalar `StateBinding`. The
**same `StateField` model** also lives on `ComponentDef.state` (§4, component-local state, Amendment #266), so a
reusable component owns data too — resolved against itself. Since **ADR-035** (schema v6, interactive state &
events), this state is also **writable**: an event handler's action mutates a declared field, and its `sample`
is both the design-time preview value *and* the initial runtime value. There is still no new state concept and
no evaluator — a handler is a closed, structured `Action` list, never an expression (see §5 `Node.handlers` and
§6). Adding `Screen.state` claimed **schema v3** (§10); component `state` later claimed **v5**; making state
writable + event handlers claimed **v6**.

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
  "root": { /* Node */ },
  "state": [ /* StateField[] — component-local state (schema 5), ADR-034 Amendment #266; omitted when empty */ ]
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

**Component-local state (schema 5, ADR-034 Amendment #266).** A `ComponentDef` may carry its own
`state: List<StateField>` — the *same* model `Screen.state` uses (§3) — so a component's internal tree can
`StateBinding`-bind, `vforge.repeat`, and populate a `vforge.dropdown` from data it owns, resolved against
**itself**, never the enclosing screen. An instance stays self-contained: at render the definition's bindings
resolve against its own `sample` data before the tree is drawn in the instance's place; at codegen the state
becomes body locals + record `data class`es private to the component's `@Composable fun`, coexisting with its
`parameters` (a prop inside the root may be a `ParamRef` *or* a `StateBinding` — distinct `PropValue` members,
never ambiguous). Additive and omitted when empty, so a stateless component is byte-identical to before; the
bump to schema 5 is the same "old builds silently drop it" reasoning as `Screen.state` (§10).

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
    val hidden: Boolean = false,                 // editor-only: excluded from render AND codegen
    val handlers: Map<String, List<Action>> = emptyMap()  // event slot -> ordered actions (ADR-035); omitted when empty
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

**`handlers`** — event slots the component exposes (a Button's `onClick`), each mapped to an **ordered
`List<Action>`** run top-to-bottom (ADR-035, schema v6). An `Action` is a closed, sealed operation —
`SetState` / `Toggle` / `Adjust` / `AppendRow` / `RemoveRow` / `Navigate` — **never an expression**: a mutating
action names a *writable* `StateBinding` target (a declared `Screen.state` / `ComponentDef.state` field of a
compatible type) and carries typed `PropValue` operands, so dispatch is a `when` at every layer (the C13
run-mode reducer, codegen, the inspector) with **no evaluator anywhere** (PF-4 stays literally true; see
SECURITY IA-*). The map is omitted when empty, so a handler-free node serializes exactly as before. Which slots
a component exposes is catalog metadata (`EventSlotDefinition`), not persisted — the same data-driven source
that describes its props.

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
    data class StateBinding(val path: String) : PropValue     // read-only data binding, §6 / ADR-034

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

### `StateBinding` — read-only data binding (schema 3, ADR-034)

Binds a prop to the declared **read-only** state of its owner — a `Screen.state` (§3) or, since schema 5, a
`ComponentDef.state` (§4, ADR-034 Amendment #266), resolved against whichever surface the prop lives in — the
design-time answer to #21's "data-driven content" without any evaluation. `path` is a **dotted identifier path** resolved by
*structural lookup*, never parsed or evaluated as Kotlin (grammar: `identifier ('.' identifier)*` — no
indexing, calls, or operators). A single segment names a scalar `StateField` (`progress`); inside a
`vforge.repeat` template the reserved `item` root names the current record (`item.title`). `item` always names
the **innermost** enclosing repeat's element, so a nested repeat's `item.*` *shadows* the outer one (nested
lists, ADR-034 Amendment #255). Resolution:

- **Canvas** substitutes the field's typed `sample` value; a `vforge.repeat` renders its template once per
  sample row (bounded to the first *N*). No live source, no compilation — so **PF-4 stays literally true**.
- **Codegen** emits the binding as a KotlinPoet **member access** (`item.title`, never string-built,
  GC-1/GC-2), reading a seeded stub (`// TODO: replace with your real data source`).
- A path that does not resolve against the declared state renders a visible placeholder and marks the node
  unverified — the same loud-failure discipline as `RawExpression` (PF-6), never a silent wrong result.

Unlike `ParamRef`, `StateBinding` was **already** a member of the closed `PropValue` hierarchy (reserved
since v1), so consuming it is not a forward-incompatible change on its own; the schema bump to **3** comes
from the companion `Screen.state` capability (§3, §10). Read-only only this release — mutation and events
are a later, separately consent-gated ADR.

A `StateBinding` may also name a **list** field, not just a scalar — the slice-2 **`vforge.dropdown`** node
(ADR-034 Amendment #253) binds its `options` prop to a list-of-record `StateField` and shows one of that
record's fields per option (an ordinary literal `optionLabel` prop). Both are additive props on a new node
type, so a dropdown is **schema-neutral** (no bump). The canvas previews the first sample row's label
read-only; codegen emits `options.forEach { item -> DropdownMenuItem(…) }` reading the same seeded stub, with
inert selection handlers (no mutation path). This and the `LazyColumn` repeat variant (§12.2, #251) are the
read-only extensions of slice 2.

**Nested lists (ADR-034 Amendment #255, schema 4).** A record field may itself be a list of records, so a
`vforge.repeat` can bind `source = item.<listField>` (a nested list on the current row) and iterate the outer
row's sub-list. Because a nested repeat replaces the `item` scope, an inner `item.*` reaches only the inner
row — to show a parent field, bind it in the *outer* template. Unlike the dropdown, this changes the serialized
shape of record fields and sample cells, so it **claims schema v4** (`M3to4`, §10). Codegen mirrors the render:
`departments.forEach { item -> … item.teams.forEach { item -> … } }` with recursive `data class`es.

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

### Version history

| Version | Trigger | Migration |
|---|---|---|
| **1** | Initial format. | — |
| **2** | `PropValue.ParamRef` — a new member of the *closed* `PropValue` union (§6), forward-incompatible (ADR-028). | `M1to2` (stamp). |
| **3** | `Screen.state` read-only data binding (§3, ADR-034) — an additive field, but a v2 build would silently drop it and misrender every `StateBinding`/`vforge.repeat`, so it is treated as semantic. | `M2to3` (stamp; v2 docs carry no state and are already valid v3). |
| **4** | Nested lists (ADR-034 Amendment #255) — `RecordField` carries a full `StateType` and a sample cell is a `SampleValue`, so a record field may be a nested list. Changes the serialized shape of v3 record fields/cells, so a *transforming* migration (not a stamp). | `M3to4` (transforms `{name,scalar}`→`{name,type}` and cell primitive→`{kind:"scalar",value}`; frozen v3 fixture). |
| **5** | Component-local state (ADR-034 Amendment #266) — `ComponentDef` gains its own `state: List<StateField>` (§3, §4), the same model `Screen.state` carries; additive, but a v4 build would silently drop it and misrender every component-local binding, so it is treated as semantic. | `M4to5` (stamp; v4 docs carry no component state and are already valid v5). |
| **6** | Interactive state & events (ADR-035) — state becomes **writable** and `Node.handlers` maps an event slot to an ordered, closed `List<Action>` (§3, §5); additive, but a v5 build would silently drop every handler and render a dead UI, so it is treated as semantic. No evaluator is introduced (PF-4 / SECURITY IA-*). | `M5to6` (stamp; v5 docs carry no handlers and are already valid v6). |
| **7** *(reserved)* | Node `responsive` per-breakpoint overrides (ADR-030, Phase 2). | `M6to7`. |

For which **app version** reads and writes which schema version, and the user-facing forward/backward
compatibility policy, see [`COMPATIBILITY.md`](../COMPATIBILITY.md).

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
2. **Lists and repeaters.** *Resolved (ADR-034, #21), slice 1 shipped.* A dynamic list is a dedicated
   `vforge.repeat` node: its `source` prop is a `PropValue.StateBinding` to a list-of-record `Screen.state`
   field (§6), and its children are the per-item template, rendered once per row against an `item` scope.
   Read-only this release — the canvas previews the field's sample rows and codegen seeds a `forEach` over a
   runnable stub. Slice 2 (ADR-034 Amendments) added a `LazyColumn` layout for the repeat (#251), a
   `vforge.dropdown` node whose `options` bind to a list field (#253), and **nested lists** (#255, schema 4) —
   a record field may itself be a list, and a repeat may bind `source = item.<listField>`, with `item`
   shadowing the innermost element. **Component-local state** (#266, schema 5) then gave `ComponentDef` its own
   `state` (§4), so all of the above work inside a reusable component against data it owns, not just on a screen.
3. **Responsive variants.** *Resolved (ADR-030), to be implemented in Phase 2.* Per-breakpoint prop
   overrides live **on the node** as an additive optional field
   `responsive: Map<String, Map<String, PropValue>>` (breakpoint-id → prop-name → override value); the
   base `props` map is the default (the smallest/compact breakpoint). Breakpoint identities are opaque
   strings to `core` and defined by the framework package's target (Material window size classes —
   `compact`/`medium`/`expanded` — for the Android target), so `core` stays framework-agnostic.
   Introducing the field will bump the schema **6 → 7** (an `M6to7` stamp) because it is a semantic
   capability old builds would silently drop, following the `ParamRef` precedent (ADR-028). (Originally
   scoped to v3; ADR-034 read-only data binding claimed v3, its nested-lists amendment (#255) claimed v4,
   its component-local-state amendment (#266) claimed v5, and ADR-035 interactive state & events claimed v6,
   so responsive slides to v7.) A separate node-id-keyed override layer was rejected — see ADR-030.
4. **Interaction/navigation.** *Resolved (ADR-035, schema v6):* state is now **writable** and a node's
   `handlers` map event slots to ordered, closed `Action` lists (§5) — `SetState`/`Toggle`/`Adjust`/
   `AppendRow`/`RemoveRow`/`Navigate` — dispatched by a `when`, never evaluated (PF-4 / SECURITY IA-*).
   `Navigate` is the structural hook for screen-to-screen navigation (#214, no generated host yet). Free-form
   expression handlers stay **out of scope** — complex logic is served by editing the generated code.

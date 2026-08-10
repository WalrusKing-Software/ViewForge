# ViewForge — Data Model

**Version:** 0.1 (planning)
**Schema version:** 1

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

**Cycle detection is required.** A user component must not, directly or transitively, contain
itself. Validate on every mutation, not just on save — a cycle will hang the renderer.

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
    val locked: Boolean = false,                 // editor-only: prevents selection/mutation
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
}
```

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

```kotlin
// Generated by ViewForge — do not edit.
// Source: Demo.vforge · schema 1

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Welcome",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
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

**This example is the first golden-file test.** Commit both files to `samples/` and assert the
transformation exactly.

---

## 12. Open modeling questions

1. **Root modifier parameter.** Generated composables take `modifier: Modifier = Modifier` by
   convention. Should the root node's own modifiers merge with the caller's, and in which order?
   (Recommendation: caller's modifier first, then the node's — matching Compose convention.)
2. **Lists and repeaters.** Static children only in Phase 1. A `LazyColumn` bound to a data source
   needs a repeater concept — real design work, deferred.
3. **Responsive variants.** Per-breakpoint prop overrides will be needed for Phase 2 (Android). Where
   do they live — on the node, or as a separate override layer? Decide before Phase 2, because
   retrofitting affects every node.
4. **Interaction/navigation.** Out of scope for v1, but reserve `StateBinding` so it can arrive
   without a schema break.

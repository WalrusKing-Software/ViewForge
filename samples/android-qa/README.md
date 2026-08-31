# Android QA sample projects

Ready-to-open `.vforge` projects for the **Phase 2 — Android** manual test pass
(`_docs/RELEASE_QA.md` §13). Instead of hand-building a project per feature, open one of these,
**Export Multiplatform Project**, and `./gradlew installDebug` onto an emulator/device.

Every file here was generated from ViewForge's own verified golden IR and checked two ways before
being committed: it passes `ProjectStore.load` (the editor's Open path — migration + safety
validation) **and** a real out-of-process Android `:assembleDebug` (each produces a debug APK with
zero manual fixes). Schema v7.

## Start here — one project, every feature

| File | What it is |
|---|---|
| **`AndroidQA.vforge`** | A single navigable project. The **Menu** screen links to one demo screen per feature; each demo has a **‹ Menu** back bar. Open / export / install **once** and tap through everything. Carries a custom theme (AN-8) applied across all screens. |

## Per-feature files (for isolating a failure)

| File | RELEASE_QA case | Exercises |
|---|---|---|
| `AN1-Widgets.vforge` | AN-1 | Basic widget render parity (buttons, text). |
| `AN2-Navigation.vforge` | AN-2 | Two-way screen navigation: Home → Details → back. |
| `AN3-Counter.vforge` | AN-3 | Interactive state & events (increment / toggle / reset, bound Text). |
| `AN4-Image.vforge` | AN-4 | Images via multiplatform `Res.drawable` (uses the `assets/` PNGs below). |
| `AN5-Responsive.vforge` | AN-5 | Responsive per-breakpoint overrides (600 / 840 dp). Compare a phone vs a tablet/resized window. |
| `AN6-Dropdown.vforge` | AN-6 | Populated dropdown from a list-valued binding. |
| `AN7-ComponentState.vforge` | AN-7 | Component-local state — each `ProfileCard` instance keeps its own state. |
| `AN7b-NestedList.vforge` | AN-7 | Nested repeated lists. |
| `AN8-Theme.vforge` | AN-8 | A customized theme applied on device. |

## `assets/`

`hero.png` (teal) and `icon.png` (orange) — two small, deliberately **different-colored** PNGs so
that on device you can confirm each image resolves independently. The editor loads asset bytes from
this sidecar `assets/` directory next to the `.vforge` file, and **Export Multiplatform Project**
bundles them into `commonMain/composeResources/drawable/`. Keep this folder beside the `.vforge`
files. `AndroidQA.vforge` and `AN4-Image.vforge` reference these two assets.

## Notes

- **AN2-Navigation** deliberately has a *real* Details target on both directions. (The codegen golden
  `Navigation.vforge` navigates to a non-existent screen on purpose — a single-screen fixture — which
  is why tapping there is a no-op; that dangling-target case is tracked as a separate bug.)
- These are QA harnesses, not showcase designs — layouts are intentionally plain so the behaviour
  under test is obvious.

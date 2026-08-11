# samples

Example `.vforge` projects used as test fixtures and for manual dogfooding (PROJECT_PLAN §4).
Not a Gradle module. Real fixtures are added alongside the IR and codegen work (M1/M6).

- `Demo.vforge` — the minimal M1 fixture (a themed title + button); round-trip test fixture.
- `Gallery.vforge` — the Phase-1 "something real" screen (M9): nested `Column`/`Row`/`Box`, `Text`,
  `Button`s, `Image`s, and a scrollable `LazyColumn`. The editor opens it on launch; it is the
  byte-identical serialization of `app`'s in-code `sampleProject()` (a test keeps the two in lockstep).
  Its image assets live on the app classpath under `app/src/main/resources/assets/`.

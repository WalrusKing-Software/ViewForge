# ViewForge — Branching & Workflow

**Model:** Git Flow — `main` holds only stable, released code; `develop` is the integration branch;
`release/x.y.z` branches stabilize and mark releases.

The intent is that **`main` is never in an experimental or in-progress state.** Anyone cloning
`main` gets code that was actually released, and any commit on `main` corresponds to a shipped
version.

---

## 1. The model

```
main      ──●──────────────────●──────────────────●────►   released code only, tagged
             \                /                  /
              \        ┌─────●─────┐      ┌─────●─────┐
release/*      \       │  0.3.0    │      │  0.4.0    │     stabilization, version bump
                \      └──▲─────┬──┘      └──▲─────┬──┘
                 \        │     │            │     │
develop   ──●──●──●──●──●─┴──●──●──●──●──●──●┴──●──●──●──►  integration; always buildable
             \    /    \    /       \      /
feature/*     ●──●      ●──●         ●────●                 short-lived
```

### Branch roles

| Branch | Lifetime | Purpose | Merges from | Merges to |
|---|---|---|---|---|
| `main` | Permanent | Released, stable code only. Every commit is a tagged release. | `release/*`, `hotfix/*` | — |
| `develop` | Permanent | Integration branch. All completed work lands here first. | `feature/*`, `fix/*`, `chore/*`, `docs/*`, `refactor/*`, `test/*`, back-merges from `release/*` and `hotfix/*` | `release/*` |
| `release/x.y.z` | Days | Stabilize a version: version bump, changelog, release-blocking fixes only. | `develop` (at cut), bugfixes committed directly | `main` **and** `develop` |
| `hotfix/x.y.z` | Hours–days | Urgent fix to a released version. | `main` | `main` **and** `develop` |
| `feature/*` etc. | Hours–days | One unit of work. | `develop` | `develop` |
| `spike/*` | Throwaway | Exploration. **Never merged.** Deleted after learning. | — | — |

---

## 2. Branch naming

```
<type>/<short-kebab-description>
```

| Type | Use | Branches from | Merges to |
|---|---|---|---|
| `feature/` | New capability | `develop` | `develop` |
| `bugfix/` (or `fix/`) | Bug fix in unreleased work | `develop` | `develop` |
| `hotfix/` | Fix to released code | `main` | `main` + `develop` |
| `refactor/` | Behavior-preserving restructure | `develop` | `develop` |
| `docs/` | Documentation only | `develop` | `develop` |
| `test/` | Tests only | `develop` | `develop` |
| `chore/` | Build, deps, tooling | `develop` | `develop` |
| `release/` | Version stabilization | `develop` | `main` + `develop` |
| `spike/` | Exploration | `develop` | **nothing** |

Examples:
`feature/modifier-reorder-ui` · `bugfix/canvas-hit-test-zoom` · `release/0.3.0` ·
`hotfix/0.3.1` · `spike/jewel-evaluation`

**Distinguish `bugfix/` from `hotfix/` strictly.** `bugfix/` fixes something not yet released and
goes through `develop` normally (`fix/` is accepted as a synonym for `bugfix/`). `hotfix/` branches
from `main` because the bug is in released code and cannot wait for the next release train; it merges
to `main` **and** back to `develop`.

**Spikes matter here.** When exploring whether an approach works, branch as a spike, learn, throw it
away, then implement cleanly. Merging exploratory code is how prototypes quietly become permanent.

---

## 3. Feature workflow

```bash
git checkout develop && git pull
git checkout -b feature/modifier-reorder-ui

# ... work, commit at logical checkpoints ...

git push -u origin feature/modifier-reorder-ui
# open PR: feature/modifier-reorder-ui -> develop
# CI green + diff reviewed -> squash merge
git branch -d feature/modifier-reorder-ui
```

**Keep feature branches short-lived.** Merge within a few days. Long-running branches diverge from
`develop` and produce painful merges — doubly true when an AI agent is generating large diffs.

Rebase onto `develop` (don't merge `develop` into the feature branch) to keep history linear before
opening the PR.

---

## 4. Release workflow

Cut a release branch when `develop` contains everything intended for the version — usually at a
milestone boundary (M1…M10 in `PROJECT_PLAN.md`).

```bash
git checkout develop && git pull
git checkout -b release/0.3.0
```

**On the release branch:**
1. Bump the version in `gradle/libs.versions.toml` / `build.gradle.kts`.
2. Update `CHANGELOG.md`.
3. Run the full verification pass — cross-OS builds, codegen compilation, screenshot diffs.
4. Fix **only release-blocking bugs**. Commit them directly to the release branch.

**No new features on a release branch.** If it isn't a fix for something broken in this release, it
goes to `develop` and waits for the next one. This rule is the entire point of having the branch.

**Finishing the release:**
```bash
# PR: release/0.3.0 -> main  (merge commit, NOT squash — preserve the release history)
git checkout main && git pull
git tag -a v0.3.0 -m "M3: selection and inspection"
git push origin v0.3.0

# CRITICAL: back-merge so fixes aren't lost
# PR: release/0.3.0 -> develop
```

**The back-merge to `develop` is the step most often forgotten**, and forgetting it silently
reintroduces bugs you already fixed. Treat it as part of the release, not a follow-up.

Delete the release branch after both merges land. The tag on `main` is the permanent marker; the
branch was scaffolding.

---

## 5. Hotfix workflow

```bash
git checkout main && git pull
git checkout -b hotfix/0.3.1
# fix, bump patch version, update CHANGELOG
# PR -> main, tag v0.3.1
# PR -> develop  (or into an open release/* branch if one exists)
```

If a `release/*` branch is open when a hotfix lands, merge the hotfix into **that branch too**, or
the in-flight release will ship with the bug reintroduced.

---

## 6. Merge strategies

| Merge | Strategy | Why |
|---|---|---|
| `feature/*` → `develop` | **Squash** | One logical change, one commit. Keeps `develop` readable. |
| `release/*` → `main` | **Merge commit** (`--no-ff`) | Preserves the release as a distinct, identifiable event in history. |
| `release/*` → `develop` | **Merge commit** | Carries release fixes back with attribution intact. |
| `hotfix/*` → `main` / `develop` | **Merge commit** | Same reasoning as releases. |

Never squash a release or hotfix merge — you lose the individual fix commits, which are exactly what
you want visible when investigating a regression later.

---

## 7. Branch protection

**`main`:**
- PRs only; no direct commits
- Only from `release/*` or `hotfix/*`
- Required CI: full matrix build, all tests, codegen compilation
- No force-push, no deletion
- Linear history not required (merge commits are intentional here)

**`develop`:**
- PRs only; no direct commits
- Required CI: build, unit tests, lint, codegen golden tests
- Branch must be up to date before merge
- No force-push, no deletion

---

## 8. Commit conventions

[Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject>

[body: why, not what]

[footer: Refs #12]
```

Scopes mirror modules: `core`, `model`, `canvas`, `inspector`, `codegen`, `compose-pkg`, `app`,
`ci`, `docs`.

```
feat(canvas): add drag-to-reparent with drop validation
fix(codegen): preserve modifier order when weight is present
refactor(model): extract PropValue into sealed hierarchy
chore(deps): bump kotlinpoet to 2.1.0
```

**Breaking changes** — including `.vforge` schema changes — get a `!`:

```
feat(model)!: split children into children + slots

BREAKING CHANGE: schemaVersion bumped to 2; migration in core/project/migrations/M1to2.kt
```

### Commit hygiene with an AI agent

- **Commit at logical checkpoints, not at session ends.** A commit should be revertible in isolation.
- **Never commit a diff you haven't read.** The commit is your assertion that you understand it.
- If a session produces a sprawling change, split it into multiple commits before opening the PR.

---

## 9. Pull requests

Every merge into `develop` or `main` goes through a PR, even solo. It provides a diff view, a CI
gate, and a written record of intent.

**Template** (`.github/pull_request_template.md`):

```markdown
## What
[One paragraph]

## Why
[Link to the feature/issue; what problem this solves]

## How
[Notable implementation decisions; anything non-obvious]

## Target
- [ ] Base branch is correct (`develop` for features/fixes; `main` only for release/hotfix)

## Verification
- [ ] Unit tests added/updated
- [ ] Golden-file tests updated (if codegen touched)
- [ ] Generated output compiles (if codegen touched)
- [ ] Manually exercised in the editor
- [ ] Schema change? → migration + fixture added, version bumped

## Risks
[What might this break?]
```

---

## 10. Versioning & releases

**Semantic versioning**, with pre-1.0 caveats:

- `0.x.y` until Phase 1 exit criteria are met. Anything may break.
- `0.MINOR.0` per completed milestone (M1…M10).
- `1.0.0` when Phase 1 is complete, dogfooded, and packaged.

**`.vforge` schema versions are independent of app versions** and tracked in `DATA_MODEL.md`. Do not
couple them — the file format will stabilize long before the app does.

**Tags live on `main` only.** A tag on `develop` or a feature branch means the model has been
violated somewhere.

### Release checklist

1. `develop` green; milestone scope complete.
2. Cut `release/x.y.z`.
3. Bump version; update `CHANGELOG.md` (Keep a Changelog format).
4. Full verification: cross-OS build, codegen compilation, screenshot diffs, security checklist
   (`SECURITY.md` §12).
5. Merge to `main` (merge commit), tag `vx.y.z`, push tag.
6. CI builds and signs cross-OS artifacts from the tag.
7. **Back-merge `release/x.y.z` → `develop`.**
8. Publish GitHub Release with notes and installers.
9. Delete the release branch.

---

## 11. Working with Claude Code

Conventions that keep agent-assisted work reviewable under this model:

1. **Always branch from `develop`**, never from `main`. `main` is for releases and hotfixes only.
   Tell Claude Code the base branch explicitly at the start of a session.
2. **One branch per feature, one session per branch** where practical. Mixing unrelated work
   produces diffs that are hard to review and hard to revert.
3. **Start each session by pointing at the relevant docs.** Name the specific feature and its
   acceptance criteria for the task at hand.
4. **Ask for a plan before implementation** on anything non-trivial. Correcting a plan is far
   cheaper than correcting a thousand-line diff.
5. **Never merge a diff you haven't read**, however good the tests look. Tests written by the same
   agent that wrote the code are not independent verification.
6. **Commit before large refactors** so there's a clean revert point.
7. **Update docs in the same PR** as the code that invalidates them. Stale docs actively mislead the
   agent in future sessions.
8. **Never let an agent perform release-branch operations unattended.** Merges to `main`, tagging,
   and back-merges are the steps where mistakes are most expensive and least visible.

---

## 12. Issue labels

| Label | Meaning |
|---|---|
| `phase-1`…`phase-5` | Roadmap phase |
| `p0`…`p3` | Priority (matches `FEATURES.md`) |
| `area:core`, `area:canvas`, `area:codegen`, `area:inspector`, `area:packaging` | Module |
| `breaking-schema` | Requires a `.vforge` migration |
| `security` | See `SECURITY.md` |
| `release-blocker` | Must be fixed before the current `release/*` ships |
| `blocked` | With a note on what it's blocked on (e.g. macOS hardware) |
| `spike` | Exploratory; expect throwaway code |

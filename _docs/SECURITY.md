# ViewForge — Security

**Version:** 0.1 (planning)

---

## 1. Why a desktop editor needs a threat model

It's tempting to skip this: no server, no accounts, no user data. But ViewForge has three properties
that make it a genuinely interesting target:

1. It **loads untrusted files** (`.vforge` projects shared between users).
2. It **writes files to arbitrary user-chosen locations** (code export).
3. It is designed to **load third-party executable extensions** (framework packages).

That third one is the real issue. Every editor with a plugin ecosystem eventually becomes a malware
distribution channel if the design doesn't account for it up front. Deciding this before the plugin
system exists is dramatically cheaper than retrofitting it.

---

## 2. Trust boundaries

| Zone | Trust | Notes |
|---|---|---|
| ViewForge application code | Trusted | Signed, distributed by the project. |
| The user | Trusted | Full control of their own machine. |
| First-party Compose package | Trusted | Ships with the app; same signing. |
| **`.vforge` project files** | **Untrusted** | May be downloaded, emailed, from a public repo. |
| **Third-party framework packages** | **Untrusted** | Arbitrary code. The dominant risk. |
| Assets (images) imported into a project | Untrusted | Decoder-level risks. |
| Generated output directory | User-controlled | Path handling must be defensive. |
| Network | N/A in v1 | **v1 makes no network requests.** Adding any is a security-relevant change requiring a doc update. |

---

## 3. Untrusted project files

**Threat:** a malicious or malformed `.vforge` causes code execution, resource exhaustion, or
information disclosure when opened.

### Requirements

| ID | Requirement |
|---|---|
| PF-1 | Deserialize with **strict** `kotlinx.serialization` config. Never use a polymorphic deserializer that can instantiate arbitrary classes by name — this is the classic deserialization-RCE pattern. Sealed hierarchies with a closed discriminator set only. |
| PF-2 | Enforce limits before/during parse: max file size, max node count, **max tree depth**. Deep nesting causes stack overflow in recursive renderers and generators. |
| PF-3 | Detect cycles in user-component references on load, not just on edit. A cycle hangs the renderer. |
| PF-4 | `RawExpression` values are **never evaluated** — only stored, displayed, and emitted as text. The canvas must not have any code-evaluation path. |
| PF-5 | Asset paths must be **project-relative and normalized**; reject anything escaping the project root (`../`, absolute paths, symlinks pointing outside, Windows UNC and drive-relative forms). |
| PF-6 | Unknown node/modifier types render as an explicit "unknown component" placeholder and are never dispatched dynamically by name. |
| PF-7 | Parse failures produce a diagnostic and leave the original file untouched. Never write a partially-parsed document back over the source. |
| PF-8 | Treat all display strings as data, not markup. Node names, project names, and prop values must not be interpreted as anything executable in the UI. |

**Design note on PF-4:** users will eventually ask for a live-evaluating expression field. That
would mean embedding a Kotlin scripting engine and executing untrusted code from project files. If
it's ever built, it needs its own threat model and an explicit, per-project user consent gate.

---

## 4. Framework packages (the dominant risk)

A framework package renders UI, generates code, and writes files. **It is not sandboxable in any
meaningful way on the JVM.** The Java `SecurityManager`, which was the traditional mechanism for
this, has been deprecated for removal and cannot be relied on.

Be honest about that rather than implying a sandbox that doesn't exist.

### Phase 1 decision

**Only the first-party Compose package exists, and it is statically linked.** No dynamic loading.
This removes the entire attack surface for the phases that matter now.

### Requirements if dynamic loading is ever implemented (Phase 5)

| ID | Requirement |
|---|---|
| FP-1 | **Explicit informed consent.** Installing a package must show an unambiguous warning that it will run with the user's full privileges. Do not present it as a sandboxed "extension." |
| FP-2 | **Signature verification.** Packages are signed; verify before load; show the publisher identity and warn loudly on unsigned or unverified packages. |
| FP-3 | **Isolated `URLClassLoader` per package** — for correctness and dependency isolation, **not** as a security boundary. Document it as such so nobody mistakes it for one. |
| FP-4 | **No auto-update or silent install** of packages. Every install/update is an explicit user action. |
| FP-5 | **Declared capabilities manifest** (filesystem, network) surfaced at install time. This aids informed consent even though it cannot be technically enforced. |
| FP-6 | **Package loading is off by default**; enabling it is a deliberate preference change. |
| FP-7 | **Fail closed.** Verification failure means refuse to load — never "warn and continue." |
| FP-8 | If a curated registry ever exists, review submissions. An unreviewed marketplace with an install button is a malware channel. |

**If real sandboxing becomes a requirement, the only credible JVM options are process isolation
(separate JVM, restricted OS-level permissions, IPC boundary) or a WASM-based plugin runtime.** Both
are major architectural undertakings. Scope them honestly rather than pretending a `ClassLoader` is
a security boundary.

---

## 5. File writing & code export

**Threat:** path traversal or overwrite causing data loss or writes outside the intended directory.

| ID | Requirement |
|---|---|
| FW-1 | All writes go through a single guarded writer. No scattered `File.writeText` calls. |
| FW-2 | Canonicalize the resolved path and assert it is inside the user-selected export root. Check **after** resolution, not on the raw string. |
| FW-3 | Reject filenames derived from project data unless sanitized: strip separators, reject reserved Windows device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`, `LPT1`–`LPT9`), strip trailing dots/spaces, cap length. |
| FW-4 | Never follow symlinks out of the export root. |
| FW-5 | Overwriting existing files requires confirmation, listing what will be replaced. |
| FW-6 | Write atomically (temp file + rename) so an interrupted export can't corrupt an existing file. |
| FW-7 | Regeneration targets a ViewForge-owned directory by default, so wholesale regeneration can't clobber hand-written user code. |
| FW-8 | Never write outside the project directory or the user-chosen export root — no writes to system paths, ever. |

---

## 6. Generated code

ViewForge emits source that the user will compile and run. **Codegen bugs are a code-integrity
issue, not merely a quality one.**

| ID | Requirement |
|---|---|
| GC-1 | Emit via **KotlinPoet's structural API**, not string concatenation. This eliminates most injection-shaped bugs by construction. |
| GC-2 | String literals must be properly escaped by the emitter — never interpolated raw. A prop value containing a quote or `$` must not be able to alter the structure of generated code. |
| GC-3 | Identifiers derived from user input (screen/component names) must be validated as legal Kotlin identifiers and must not collide with keywords. |
| GC-4 | `RawExpression` content is emitted verbatim **by design**. The user is the author. Mark such nodes as unverified in the UI and document that they bypass validation. |
| GC-5 | Generated files carry a header identifying them as generated and naming the source file. |
| GC-6 | CI compiles generated fixtures — the strongest available check that output is well-formed. |

---

## 7. Assets & image decoding

Image decoders are historically a rich source of memory-safety bugs, and ViewForge decodes
user-supplied images.

| ID | Requirement |
|---|---|
| AS-1 | Enforce max file size and max pixel dimensions before decode; reject decompression bombs. |
| AS-2 | Validate declared type against actual content; don't trust the extension. |
| AS-3 | Decode failures are caught and shown as a broken-asset placeholder, never a crash. |
| AS-4 | Copy assets into the project directory on import; never reference arbitrary external paths. |
| AS-5 | Strip or ignore embedded metadata. EXIF can carry GPS coordinates — a real privacy leak if projects are shared. |

---

## 8. Dependency supply chain

The project depends on a substantial JVM/Gradle tree, which is a realistic compromise vector.

| ID | Requirement |
|---|---|
| DS-1 | **Pin exact versions** in `gradle/libs.versions.toml`. No dynamic versions (`+`, `latest.release`) — ever. |
| DS-2 | Commit **Gradle dependency verification** (checksums/signatures) so a substituted artifact fails the build. |
| DS-3 | Enable Dependabot/Renovate with **manual review**, not auto-merge. |
| DS-4 | Run vulnerability scanning in CI (e.g. OWASP Dependency-Check or `gradle-dependency-analyze`). |
| DS-5 | Use the Gradle wrapper with a **verified checksum**; the wrapper JAR is a known attack vector. |
| DS-6 | Review new transitive dependencies before adding a library. Prefer fewer, well-maintained dependencies. |
| DS-7 | CI secrets (signing keys) are never exposed to PR builds from forks. |

---

## 9. Distribution

| ID | Requirement |
|---|---|
| DI-1 | **Sign release artifacts.** Unsigned desktop installers train users to bypass OS protections — the exact habit that makes them vulnerable to everything else. |
| DI-2 | Publish checksums for all release artifacts. |
| DI-3 | Build releases in CI from a tagged commit, not from a developer machine. |
| DI-4 | If auto-update is added, updates must be signature-verified and served over TLS. An unauthenticated auto-updater is a remote code execution channel. |
| DI-5 | Reproducible builds where practical. |

---

## 10. Privacy

| ID | Requirement |
|---|---|
| PR-1 | **No telemetry in v1.** If ever added: opt-in, disclosed, and never including project content. |
| PR-2 | Crash logs stay local. Never auto-transmit. |
| PR-3 | Logs must not contain project content, file paths outside the project, or environment variables. |
| PR-4 | `.vforge` files must contain no absolute paths, usernames, or machine identifiers — they will be committed to public repos. **This is a concrete data-leak vector worth testing for explicitly.** |
| PR-5 | Recent-projects lists are local-only. |

---

## 11. What is explicitly out of scope

Stated so the boundaries are decisions rather than gaps:

- **Defending against a compromised host OS.** If the machine is compromised, ViewForge cannot help.
- **Sandboxing first-party code.** The app runs with the user's privileges by design.
- **Protecting the user from their own `RawExpression` code.**
- **Malicious-user threats.** Single-user local tool; no multi-tenancy, no privilege model.

---

## 12. Pre-release security checklist

Before any public release:

- [ ] No dynamic version ranges in the version catalog
- [ ] Dependency verification enabled and committed
- [ ] Gradle wrapper checksum verified
- [ ] Vulnerability scan clean (or exceptions documented)
- [ ] Path-traversal tests pass for export (including Windows-specific forms)
- [ ] Malformed/hostile `.vforge` fixtures fail safely — depth bomb, huge node count, cyclic refs,
      traversal in asset paths
- [ ] Generated code escaping tests pass (quotes, `$`, newlines, unicode in prop values)
- [ ] No absolute paths or usernames in a saved `.vforge`
- [ ] Release artifacts signed; checksums published
- [ ] No network calls present (verify empirically, not by assumption)
- [ ] Crash logs contain no project content

---

## 13. Reporting

Once public, add a `SECURITY.md` at the repo root with a private disclosure channel and expected
response time. Do not require reporters to use public issues for vulnerabilities.

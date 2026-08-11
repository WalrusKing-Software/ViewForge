# ViewForge — Installing & Packaging

How ViewForge is packaged into native installers, how to build them, and how an end user installs the
result. Packaging decisions and rationale live in [ADR-022](DECISIONS.md); this doc is the operational
"how".

ViewForge uses **vanilla jpackage** through Compose Desktop's `nativeDistributions` (configured in
`app/build.gradle.kts`). No Conveyor, no auto-update, no network — installers are plain OS-native
packages the user downloads and runs (ADR-011, ADR-022).

---

## 1. Installing a release (end users)

Download the installer for your OS from the GitHub Release, **verify its checksum**, then install.

Every artifact ships a `<file>.sha256` sidecar. Verify before installing:

- **Windows (PowerShell):** `Get-FileHash ViewForge-<version>.msi -Algorithm SHA256` — compare to the
  `.sha256` file.
- **Linux:** `sha256sum -c viewforge_<version>_amd64.deb.sha256`

### Windows

- **`ViewForge-<version>.msi`** — recommended. Double-click, follow the wizard. Installs to
  `Program Files`, adds a Start-menu entry under **ViewForge** and a desktop shortcut, and registers
  for clean uninstall via *Apps & features*. Upgrading with a newer MSI replaces the prior install
  (stable `upgradeUuid`).
- **`ViewForge-<version>.exe`** — the same installer as a self-contained executable, for contexts
  where an `.exe` is preferred over an `.msi`.
- **Signing:** a real release is Authenticode-signed; Windows SmartScreen shows the verified publisher.
  An unsigned build (see below) triggers a SmartScreen warning — expected for local/dev builds only.

### Linux

- **`.deb`** (Debian/Ubuntu): `sudo apt install ./viewforge_<version>_amd64.deb`
- **`.rpm`** (Fedora/RHEL/openSUSE): `sudo dnf install ./viewforge-<version>.x86_64.rpm`
- Installs under `/opt/viewforge`, adds a **Development** category desktop entry. A release `.deb`/`.rpm`
  ships a detached `.asc` GPG signature you can verify with the project's published public key.

### macOS

Not packaged in Phase 1 — see [§5](#5-known-limitations--follow-ups).

---

## 2. Building installers locally (developers)

Requires **JDK 21** (jpackage; TECHNICAL_NOTES §12). The Compose Gradle plugin unpacks its own WiX for
Windows, so no separate WiX install is needed.

```bash
# Whatever the current OS supports:
./gradlew :app:packageDistributionForCurrentOS

# Or a specific format:
./gradlew :app:packageMsi     # Windows installer (.msi)   — verified locally
./gradlew :app:packageExe     # Windows installer (.exe)
./gradlew :app:packageDeb     # Debian/Ubuntu (.deb)       — Linux host only
./gradlew :app:packageRpm     # Fedora/RHEL (.rpm)         — Linux host only

# App image (no installer, fastest smoke test — no WiX/jpackage installer step):
./gradlew :app:createDistributable
```

Output lands under `app/build/compose/binaries/main/<format>/`.

**Platform notes**

- You can only build a given OS's installer **on that OS** (jpackage is not a cross-compiler). Windows
  installers build on Windows; `.deb`/`.rpm` build on Linux.
- On Linux, jpackage shells out to distro tools: `.deb` needs `fakeroot`, `.rpm` needs `rpmbuild`
  (`sudo apt-get install fakeroot rpm`).
- **Local builds are unsigned by design.** Signing happens only in the tagged CI release
  (SECURITY DI-3) — a laptop build is for testing the package, not for distribution.

### Version

The installer version comes from **one place**: `viewforge.version` in `gradle.properties`. Bump it on
the `release/x.y.z` branch. jpackage constraints: Windows MSI wants `major.minor.micro`
(major 0–255 / minor 0–255 / micro 0–65535); macOS requires `major >= 1`.

### Runtime image size

The build sets `includeAllModules = true`, so the bundled Java runtime is complete rather than trimmed —
the installer is large (~90–100 MB) but the packaged app is guaranteed to have the same module set as the
verified `run` build. Trimming with the `suggestRuntimeModules` task is a deliberate later optimisation
(ADR-022), not done blindly.

---

## 3. Cutting a signed release (CI)

`.github/workflows/release.yml` (GitHub-only; the homelab Forgejo has no Windows runners) triggers on a
pushed **`v*` tag** and:

1. Creates a **draft** GitHub Release for the tag.
2. Builds installers on a per-OS matrix (Windows Msi/Exe, Linux Deb/Rpm) from the tagged commit (DI-3).
3. **Signs** them if the signing secrets are present (DI-1) — see below.
4. Generates **SHA-256** `.sha256` sidecars (DI-2) and uploads everything to the release.
5. **Publishes** the release once every job succeeds.

Cut a release by tagging on `main` after the `release/*` merge (BRANCHING §10):

```bash
git tag -a v0.1.0 -m "M10: packaging"
git push origin v0.1.0
```

### Signing secrets

Configure these as repository secrets. **If absent, the workflow still builds installers but leaves them
unsigned and warns** — never publish an unsigned public release (DI-1).

| Secret | Purpose |
|---|---|
| `WINDOWS_PFX_BASE64` | Base64-encoded Authenticode code-signing certificate (`.pfx`). |
| `WINDOWS_PFX_PASSWORD` | Password for the `.pfx`. |
| `LINUX_GPG_PRIVATE_KEY` | ASCII-armored GPG private key for detached `.deb`/`.rpm` signatures. |
| `LINUX_GPG_PASSPHRASE` | Passphrase for the GPG key. |

Secrets are never exposed to fork pull-request builds: the workflow only runs on tag pushes to this repo
(SECURITY DS-7).

---

## 4. Icons

Committed under `app/src/main/resources/packaging/`:

- `icon.ico` — Windows
- `icon.png` — Linux (512×512)

These are **generated placeholders** (an indigo "VF" mark). Replacing them with a real branded icon set
(including a macOS `.icns`) is a follow-up; drop the files in at the same paths and the build picks them
up.

---

## 5. Known limitations & follow-ups

- **macOS packaging is not shipped in Phase 1.** jpackage rejects a version with major `0`, so a pre-1.0
  `.dmg` can't carry an honest version, and it needs a Mac runner plus a real `.icns` to verify. Adding
  it is localised: the `Dmg` target format, a `macOS { }` block, and a mac-valid version (ADR-022).
- **Installer size** until runtime modules are trimmed (`includeAllModules`, above).
- **Branded icons** (§4).
- **No auto-update.** By design (ADR-011). Users install new versions manually. Any future updater is a
  network-relevant decision requiring a threat-model update (SECURITY DI-4).

# Security Policy

## Reporting a vulnerability

**Please do not open a public issue for security vulnerabilities.**

Report them privately by email to **walruskingsoftware@gmail.com** with:

- a description of the issue and its impact,
- steps to reproduce (or a proof of concept), and
- the affected version (e.g. `v0.1.0-alpha-1`) and platform.

You can expect an acknowledgement within a few days. We'll work with you on a fix and coordinate
disclosure; please give us reasonable time to release a patch before making details public.

## Scope

ViewForge is a **local-first, offline desktop application** — it makes no network calls (ADR-011) and
has no server, accounts, or telemetry. The security-relevant surfaces are:

- **Untrusted `.vforge` project files** (may be shared/downloaded) — parsing, limits, cycles.
- **Code export / file writing** — path handling and overwrite safety.
- **Generated Kotlin** — escaping and structural correctness.
- **Imported assets** (images) — decoder safety.

The full threat model, trust boundaries, and per-area requirements are documented in
[`_docs/SECURITY.md`](_docs/SECURITY.md). Third-party framework packages (dynamic plugin loading) are a
**Phase 5** concern and are not present in v0.1.0-alpha-1.

## Supported versions

This project is early (v0.1.x). Security fixes target the latest released version.

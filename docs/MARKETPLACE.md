# Doppler — JetBrains Marketplace listing

> Source-of-truth copy for the marketplace description.

---

## Tagline (≤80 chars)

Inject Doppler secrets into run configs and manage them from a tool window.

## Short description (≤140 chars)

Run configurations get Doppler secrets in their environment, automatically. No
`.env` files, no `doppler run --` wrapping, nothing on disk.

## Long description

**Doppler for JetBrains** brings your [Doppler](https://doppler.com)-managed
secrets directly into the IDE. Press **Run** on any supported configuration —
JVM, Gradle, Node.js, or Python — and Doppler values land in the launched
process's environment automatically. No `.env` files. No `doppler run --`
wrapper script. Nothing is ever written to disk by the plugin.

A built-in tool window lets you browse, copy, edit, add, or delete secrets for
the active project / config without leaving the IDE.

### Features

- **Multi-language secret injection** at launch time:
  - **JVM** — Application (Java + Kotlin), JUnit, TestNG, Spring Boot
  - **Gradle** — every Gradle run configuration (default IDEA delegation, Tooling API)
  - **Node.js / npm / yarn / pnpm / Jest / Vitest**
  - **Python** — script, pytest, unittest, Django, Flask, FastAPI
- **Tool window** — table of every secret in the active config; reveal, copy,
  edit, add, delete. Values masked by default.
- **Settings page** — pick the Doppler project & config, set cache TTL, point
  to a custom CLI binary, run a connection test.
- **Local-wins merge** — env vars set on a run config shadow Doppler values; a
  one-time-per-session balloon lists the shadowed *keys* (never values).
- **Hard-fail on injection error** — missing CLI, unauthenticated, or fetch
  error → launch is aborted with an actionable notification, never a partial
  start.
- **In-memory TTL cache** per `(project, config)` (default 60 s, manually
  refreshable from the tool window).

### Compatibility

- IntelliJ IDEA (Ultimate + Community), PyCharm (Pro + CE), WebStorm,
  PhpStorm, GoLand, Rider, RubyMine — **2026.1 or later**.
- Rider users get the tool window; run-config injection for .NET is not
  included in this release.

### Requirements

- [Doppler CLI](https://docs.doppler.com/docs/install-cli) installed and
  authenticated (`doppler login`).
- The plugin **never** stores Doppler tokens or talks to the Doppler HTTP API
  directly. All Doppler interactions go through the local `doppler`
  subprocess; auth lives in the CLI's keychain integration.

### Security

- No secret value is ever written to disk, indexed, logged, included in a
  notification body, or passed as a CLI argument (writes use stdin, never
  argv).
- Settings stored in `.idea/doppler.xml` contain only project / config slugs
  and toggles — no secret values, no token.
- Notifications report **key names** on conflicts, never values.

### Links

- **Source**: <https://github.com/amasotti/doppler-intellj-plugin>
- **Issues**: <https://github.com/amasotti/doppler-intellj-plugin/issues>
- **License**: MIT


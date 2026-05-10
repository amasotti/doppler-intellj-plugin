# Doppler for JetBrains IDEs

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/31683-doppler.svg?label=marketplace)](https://plugins.jetbrains.com/plugin/31683-doppler)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/31683-doppler.svg)](https://plugins.jetbrains.com/plugin/31683-doppler)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/IntelliJ%20Platform-2026.1%2B-orange.svg)](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)

Inject [Doppler](https://doppler.com)-managed secrets into JetBrains run configurations and browse, copy, edit
or delete them from a built-in tool window — without leaving the IDE.

> **Install from the JetBrains Marketplace:** <https://plugins.jetbrains.com/plugin/31683-doppler>

Press **Run**, secrets land in the process environment. No `.env` files. No `doppler run --` wrapping.
Nothing is ever written to disk by the plugin.

---

## Highlights

- **Multi-language secret injection** — Doppler secrets merged into the launch environment for:
  - **JVM family** (IDEA, IDEA Ultimate) — Application (Java + Kotlin), JUnit, TestNG, Spring Boot Application
  - **Gradle** (any IDE with the Gradle plugin) — every Gradle run configuration
  - **Node.js / npm / yarn / pnpm / Jest / Vitest** (WebStorm, IDEA Ultimate, PyCharm Pro)
  - **Python — script, pytest, unittest, Django, Flask, FastAPI** (PyCharm CE/Pro, IDEA Ultimate with Python plugin)
  - **Rider — tool window only**, run-config injection not yet supported (track it via [#issues](https://github.com/amasotti/doppler-intellj-plugin/issues) if you need it)
- **Tool window** — review every secret of the active config in a table; copy, reveal, edit,
  add or delete entries. Values are masked by default and never persisted by the plugin.
- **Settings page** — pick the Doppler project & config, set the cache TTL,
  point to a custom CLI binary, run a connection test.
- **Local-wins merge** — environment variables defined directly on a run configuration
  shadow Doppler-managed values. A one-time-per-session balloon lists the shadowed **keys** (never values).
- **Hard-fail on injection error** — if the CLI is missing, unauthenticated, or returns an
  error, the launch is aborted with an actionable notification. Processes never start with
  partial secret state.
- **In-memory TTL cache** — per `(project, config)` pair, default 60 s, manually invalidated
  by the tool window's Refresh action.

---

## Requirements

| Requirement                                                 | Version                            |
|-------------------------------------------------------------|------------------------------------|
| [Doppler CLI](https://docs.doppler.com/docs/install-cli)    | any recent release                 |
| `doppler login` completed                                   | —                                  |
| JetBrains IDE (IDEA, PyCharm, WebStorm, GoLand, Rider, ...) | 2026.1 or later                    |
| JDK (only for **building from source**)                     | 21                                 |
| Gradle (only for **building from source**, wrapper bundled) | 9.1+                               |

The plugin **never** stores Doppler tokens or talks to the Doppler API directly. Every Doppler
interaction goes through the local `doppler` subprocess; authentication is handled by the
CLI's keychain integration.

---

## Installation

### From JetBrains Marketplace (recommended)

Open `Settings → Plugins → Marketplace`, search **Doppler**, install, restart the IDE.
Direct link: <https://plugins.jetbrains.com/plugin/31683-doppler>.

### From a release `.zip`

1. Grab the latest signed `.zip` from
   [GitHub Releases](https://github.com/amasotti/doppler-intellj-plugin/releases).
2. `Settings → Plugins → ⚙ → Install Plugin from Disk…` → select the `.zip`.
3. Restart the IDE.

### From source

```bash
git clone https://github.com/amasotti/doppler-intellj-plugin.git
cd doppler-intellj-plugin
./gradlew buildPlugin                    # build/distributions/*.zip
```

---

## Setup

The plugin assumes the Doppler CLI is installed and authenticated.
If `doppler login` has never been run, do that first — the plugin cannot recover from a
missing CLI session and will not store credentials of its own.

1. Open **Settings → Tools → Doppler**.
2. Tick *Enable Doppler injection for this project*.
3. (Optional, but required on macOS GUI launches when the CLI lives outside the system PATH)
   set **Custom CLI path** to the absolute path of `doppler` (e.g. `/opt/homebrew/bin/doppler`).
4. Click **Test connection**. After ≤ 10 s the label shows
   `Connected as you@example.com (CLI x.y.z)`.
5. Pick the **Doppler project** and **Doppler config** from the dropdowns. The lists are
   populated from the CLI asynchronously.
6. (Optional) tweak **Cache TTL** (default 60 s).
7. Apply / OK.

> The settings live in `.idea/doppler.xml`. They contain only project / config slugs
> and toggles — **no secret values, no token**. Commit the file or `.gitignore` it; either
> works for the plugin.

### macOS sandbox-IDE caveat

On macOS the sandbox IDE process started by `./gradlew runIde` does **not** inherit the
full shell PATH. Use the *Custom CLI path* field; once filled in, close and reopen the
settings dialog so the project / config dropdowns can fetch their items using the saved path.

---

## Daily use

### Run / Debug a configuration

Launch any supported run configuration the way you normally would. The plugin merges
Doppler secrets into the launched process environment before the JVM (or Gradle) starts.
No special run-config flag is needed.

- **JVM family** (Application Java + Kotlin, JUnit, TestNG, Spring Boot) — via
  `RunConfigurationExtension.updateJavaParameters`.
- **Gradle** — via `ExternalSystemRunConfigurationExtension.patchCommandLine`.
- **Node.js / npm / yarn / pnpm / Jest / Vitest** — via
  `AbstractNodeRunConfigurationExtension.createLaunchSession` (Node's `patchCommandLine`
  is `final`; env injection happens in `addNodeOptionsTo` on the launch session).
- **Python** (script, pytest, unittest, Django, Flask, FastAPI) — via
  `PythonRunConfigurationExtension.patchCommandLine`.
- **Rider (.NET)** — tool window only, no run-config injection in this version.

If a run configuration explicitly sets an env var that Doppler also exports, the local
value wins. The first time per session that this happens for a given configuration,
the IDE shows a balloon listing the shadowed keys (never the values) so the divergence
between local and CI/staging is visible.

### Tool window

`View → Tool Windows → Doppler` (right side by default) shows a table of every secret in
the active project / config. Available actions:

- **Refresh** — re-fetch from the CLI (also invalidates the cache).
- **Add secret** — inline form; the value is sent to the CLI via stdin, never argv.
- **Reveal / hide** — toggle masking for the selected row.
- **Copy value** — to the clipboard. Never to a log or notification.
- **Delete** — confirmation dialog; deletion goes through `doppler secrets delete`.
- **Edit** — double-click a *revealed* value cell. Save batches the changes through
  `doppler secrets set` (one call per modified key) and refreshes the table.
- **Right-click row** — same actions plus *Copy name*.

Masked cells are intentionally non-editable: if Swing committed the placeholder string
as the new value, the original secret would be destroyed.

---

## Architecture & design

The plugin is split into independent layers (`cli/`, `cache/`, `service/`, `injection/`,
`settings/`, `ui/`, `notification/`) with strict dependency rules and a single throw-site
between the service and the injectors. The full architecture, sequence diagrams, threading
model, and security boundaries are documented in
[`docs/architecture.md`](docs/architecture.md).

---

## Security

- No secret value is ever written to disk, indexed, logged, included in a notification body,
  or passed as a CLI argument.
- Secret values for `doppler secrets set` are piped through `stdin`, never argv —
  so they cannot leak via `ps`, `/proc`, or process accounting.
- The plugin never reads, writes, or transmits Doppler tokens; CLI auth lives in the
  CLI's keychain integration.
- Conflict notifications report **key names**, never values.
- Internal data classes that carry secret values (cache entries, table rows, the merged
  env map) override `toString()` to a redacted summary.
- The notification group is registered with `isLogByDefault="false"` — balloons fade
  away rather than persisting in the Event Log.

---

## Building, testing, packaging

```bash
./gradlew buildPlugin              # produces build/distributions/*.zip
./gradlew test                     # JUnit 5 + IntelliJ Platform test framework
./gradlew detekt                   # static analysis
./gradlew runIde                   # sandboxed IDEA Ultimate 2026.1 with the plugin loaded
./gradlew runWebStorm              # cross-IDE smoke test (also: runPyCharm, runPhpStorm, runRider)
./gradlew verifyPlugin             # validate plugin.xml + archive structure
```

Tests never call the real Doppler CLI or API — fixtures + fake shell-script binaries cover
every code path in `DopplerCliClient`. End-to-end verification is a manual checklist per
release; see `docs/architecture.md`.

Plugin signing and Marketplace publishing are wired through environment variables
(`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, `PUBLISH_TOKEN`).
Step-by-step setup lives in [`docs/SIGNING.md`](docs/SIGNING.md).

---

## Troubleshooting

- ***Test connection* stays on `Testing…`** — the CLI subprocess hit the 10 s timeout.
  Check `idea.log` for `doppler` entries and verify the CLI path is correct.
- **Project / config dropdowns are empty** — the async load fires when Settings opens.
  Save the CLI path first (*Apply*), then close and reopen the dialog.
- **Run launches without secrets** — verify *Enable Doppler injection* is on, project /
  config are not blank, and the relevant family plugin is present (`com.intellij.java`,
  `com.intellij.gradle`, `JavaScript`, or `com.intellij.modules.python` depending on
  the run-config type).
- **Balloon `Doppler fetch failed: …`** — the CLI returned non-zero. The message body
  is the CLI's stderr verbatim; fix the underlying CLI error and retry.
- **`doppler` not on PATH (sandbox IDE on macOS)** — use the **Custom CLI path** field.
  The GUI process does not inherit the shell PATH.

If the issue persists, check `~/Library/Logs/JetBrains/<IDE>/idea.log` (or the equivalent
on Linux / Windows) and search for `doppler` or `DopplerSettings`.

---

## Contributing

This is a personal project; issues and PRs are welcome but the architectural rules in
[`AGENTS.md`](AGENTS.md) are the gate. In short: separation of layers, no secret-value
leakage paths, KISS / YAGNI, and a real test for any behaviour change.

---

## License

MIT — see [`LICENSE`](LICENSE).

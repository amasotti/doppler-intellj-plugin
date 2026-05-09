# Doppler JetBrains Plugin

Inject [Doppler](https://doppler.com)-managed secrets into JetBrains run configurations.
Press **Run** — secrets arrive in the process environment. No `.env` files. No manual `doppler run --` wrapping.

Also provides a tool window to browse and edit secrets without leaving the IDE.

---

## Features (v1 in progress)

- **Secret injection** — Java, Kotlin, JUnit, TestNG, Spring Boot, and Gradle run configurations get Doppler secrets merged into their environment on launch.
- **Local-wins policy** — run-configuration env vars shadow Doppler values; one-time balloon warning lists shadowed keys (never values).
- **Hard abort on failure** — if the CLI is missing, not authenticated, or returns an error, the launch is aborted and a balloon notification explains why. Secrets are never silently absent.
- **Settings page** — Settings → Tools → Doppler: pick project and config, set cache TTL, point to a custom CLI binary, test the connection.
- **In-memory cache** — secrets cached per project/config pair with configurable TTL (default 60 s).

Planned: tool window for browsing and editing secrets, status bar widget.

---

## Prerequisites

| Requirement | Version |
|-------------|---------|
| [Doppler CLI](https://docs.doppler.com/docs/install-cli) | Any recent |
| `doppler login` completed | — |
| IntelliJ IDEA (Ultimate or Community) | 2024.2+ |
| JDK (build) | 21+ |
| Gradle | 9.1+ (wrapper included) |

The plugin **never** stores tokens or makes direct HTTP calls to the Doppler API. Everything goes through the `doppler` subprocess.

---

## Building from source

```bash
git clone https://github.com/amasotti/doppler-intellj-plugin
cd doppler-intellj-plugin

# Build the plugin zip (output: build/distributions/*.zip)
./gradlew buildPlugin

# Run all unit tests
./gradlew test

# Run static analysis (detekt)
./gradlew detekt
```

---

## Running tests

```bash
./gradlew test
```

Tests use IntelliJ Platform's test framework (`@TestApplication`, `projectFixture()`).
No real Doppler CLI or API call is made — tests use fake shell-script CLIs in temp directories.

To run a single test class:

```bash
./gradlew test --tests "com.tonihacks.doppler.cli.DopplerCliClientTest"
```

---

## Local testing in a sandbox IDE

```bash
./gradlew runIde
```

This starts a sandboxed IntelliJ Community 2024.2 instance with the plugin installed.

### Known issue: PATH in sandbox IDE

On macOS the sandbox IDE process does **not** inherit the full shell PATH. Commands installed via Homebrew (e.g. `/opt/homebrew/bin/doppler`) are not found by `PathEnvironmentVariableUtil.findInPath`.

**Workaround — initial setup flow:**

1. Open **Settings → Tools → Doppler**.
2. Paste the full CLI path into **Custom CLI path** (e.g. `/opt/homebrew/bin/doppler`).
   ```bash
   which doppler   # prints the path to paste
   ```
3. Click **Apply** (saves the path to `.idea/doppler.xml`).
4. Click **Test connection** — after ≤ 10 s the label shows `Connected as you@email.com (CLI x.y.z)`.
5. **Close and reopen** Settings → Tools → Doppler. Now the project and config dropdowns populate from the CLI.
6. Select your Doppler project and config, click **Apply / OK**.
7. Run any supported run configuration — Doppler secrets are injected into the process environment.

> **Why close and reopen?** The project/config lists are loaded asynchronously when the settings panel opens (step 1). At that point the CLI path field is still empty (nothing is saved yet). After Apply saves the path, reopening triggers a fresh async load using the saved path.

### Verifying injection

Add a **Shell Script** run configuration (or a simple Java `main` that prints `System.getenv()`) and run it. You should see your Doppler secrets in the output.

For Gradle: add a `printenv` task to any `build.gradle.kts`:

```kotlin
tasks.register("printenv") {
    doLast { System.getenv().forEach { (k, v) -> println("$k=$v") } }
}
```

Run it via Gradle tool window or a Gradle run configuration — Doppler secrets appear in the output.

---

## Project structure

```
src/main/kotlin/com/tonihacks/doppler/
├── cli/           # DopplerCliClient — subprocess wrapper, zero IntelliJ deps
├── cache/         # SecretCache — in-memory TTL cache
├── service/       # DopplerProjectService — orchestrates CLI + cache + settings
├── settings/      # DopplerSettingsState (persistence) + Settings UI
├── injection/
│   ├── core/      # SecretMerger, OverrideTracker — platform-agnostic
│   ├── java/      # RunConfigurationExtension for JVM family
│   └── gradle/    # ExternalSystemRunConfigurationExtension for Gradle
├── notification/  # DopplerNotifier — single funnel for user messages
└── DopplerBundle  # Resource bundle accessor
```

Layer rules (enforced by code review):
- `ui/` and `settings/` may depend on `service/` and `cli/`.
- `service/` depends on `cli/`, `cache/`, `settings/`.
- `cli/` and `cache/` have zero IntelliJ Platform dependencies (except `GeneralCommandLine`).
- Secrets never touch disk, logs, notifications, or exception messages.

---

## Troubleshooting

### "Testing…" label never changes

The CLI call has a 10-second timeout. Wait ≥ 10 s after clicking.
If it still does not update, check the sandbox log:

```
build/idea-sandbox/IC-242/system/log/idea.log
```

Search for `doppler` or `DopplerSettings`.

### Combos stay empty

Follow the **close and reopen** flow described above.
The project/config lists load only when the settings panel opens and only if the CLI path is already saved.

### Injection does not fire

- Confirm **Enable Doppler injection for this project** is checked.
- Confirm project and config fields are not blank.
- Check the Run tool window — a red balloon error appears when injection fails (CLI missing, auth expired, wrong config name, etc.).
- Run configurations launched via Gradle use the Gradle injector; launched directly (Application, JUnit, Spring Boot) use the JVM injector. Both require the corresponding IntelliJ plugin (`com.intellij.gradle`, `com.intellij.java`) to be present.

---

## Security

- No secret value is ever written to disk, logged, included in a notification body, or passed as a CLI argument.
- Secret values for `doppler secrets set` are piped via stdin, never argv.
- The plugin never reads, stores, or transmits Doppler tokens — all auth lives in the CLI's keychain integration.
- Conflict notifications list shadowed **key names** only, never values.

See `AGENTS.md §6` for the full security checklist applied before every PR.

---

## License

MIT

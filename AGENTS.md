# agents.md — AI Agent Guidelines

> **Audience:** Claude Code (and other AI coding assistants) working on the Doppler JetBrains Plugin.
> **Purpose:** Encode the project's design philosophy, code conventions, and operating rules so an AI agent produces
> work consistent with the spec and with how Toni works.

---

## 0. Read this first

Before doing anything, read:

1. `docs/spec.md` — the source of truth for what is being built
2. This file — the source of truth for *how* it is built and how you should behave
3. The **IntelliJ Platform Plugin SDK docs** — the source of truth for everything about IntelliJ APIs

If a request conflicts with `spec.md`, stop and ask. Do not silently change scope.

If `spec.md` conflicts with the SDK docs (e.g., wrong extension point name, deprecated API, wrong Gradle plugin ID), *
*the SDK docs win.**
Surface the conflict in chat and propose a spec update before coding around it.

Work mode is iterative: you will not produce perfect code on the first try and should not attempt to complete large
tasks in one go.
The user that is guiding you is an expert developer himself and will provide feedback and course corrections as you go.

Documentation is important. Treat stale documentation as a bug. If you implement something new or correct something,
never consider the task done until the relevant documentation is updated as well.

### 0.1 Canonical SDK references

Check the live page — the SDK evolves and training data goes stale.

| Topic                                                 | URL                                                                                      |
|-------------------------------------------------------|------------------------------------------------------------------------------------------|
| `plugin.xml` reference                                | <https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html>             |
| **Run Configurations** (architecturally critical)     | <https://plugins.jetbrains.com/docs/intellij/run-configurations.html>                    |
| **IntelliJ Platform Gradle Plugin (2.x)**             | <https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html> |
| Extension Point list                                  | <https://plugins.jetbrains.com/docs/intellij/extension-point-list.html>                  |
| Doppler CLI reference                                 | <https://docs.doppler.com/reference/cli>                                                 |
| IntelliJ Community source (verify EP names / classes) | <https://github.com/JetBrains/intellij-community>                                        |

For broader topics (signing, plugin structure, Kotlin usage, FAQ, plugin template) start at
<https://plugins.jetbrains.com/docs/intellij/welcome.html>.

### 0.2 Specific facts the SDK docs already settled (don't re-derive)

- **Gradle plugin ID is `org.jetbrains.intellij.platform`** (2.x). The 1.x `org.jetbrains.intellij` is frozen and must
  not be used.
- **Minimum Gradle 9.1, minimum JDK 21** for the 2.x Gradle plugin. This project is using gradle 9.2 and JDK 25, so we're good.
- **There is no single platform-wide "modify any run configuration" extension point.** Each family has its own subclass
  of `RunConfigurationExtensionBase`. Do not invent a generic hook.
- **`patchCommandLine` is *not* the universal Gradle env-injection hook.** The default *Build and run using: Gradle*
  delegation goes through the Tooling API, which reads env from `GradleExecutionSettings.env`. The load-bearing hook
  is `GradleExecutionHelperExtension.configureSettings` (EP `org.jetbrains.plugins.gradle.executionHelperExtension`).
  **Registered ≠ invoked** — before adding a new family adapter, instrument with `thisLogger().info(...)` and confirm
  it fires in `idea.log` for the runtime path you care about.
- **Pin marketplace plugin deps to a build whose `since` matches the IDE GA you target.** `intellijIdeaUltimate("2026.1")`
  resolves to a specific sub-build (e.g. `IU-261.22158.277`); a marketplace plugin pinned to a *later* 261.x sub-build
  fails to load (SEVERE in `idea.log`, balloon in the IDE). Use
  `https://plugins.jetbrains.com/api/plugins/<id>/updates` to find a compatible build.
- **Don't set `until-build`** in `plugin.xml` unless there's a documented reason. Default = open-ended compatibility.
- **Use the Plugin Template** as the project bootstrap. Don't hand-roll CI workflows when the template ships them.

---

## 1. Operating principles

### 1.1 Be a collaborator, not a generator

- **Ask before assuming.** When the requirement is genuinely ambiguous, ask one focused question. When you can make a
  sensible default, state the assumption inline and proceed.
- **Disagree when warranted.** If a task contradicts the spec, KISS, YAGNI, or the security rules in §6 below, push back
  with a short reasoning before complying. "User asked for it" is not enough.
- **Surface unknowns.** If you don't know an IntelliJ Platform API's behavior, say so and propose a verification step (
  e.g., write a small probe test) rather than inventing.

### 1.2 KISS / YAGNI by default

- Build the minimum that satisfies the current ticket. Do not preemptively generalize, add config flags, build "
  framework" abstractions, or speculate on future requirements.
- If you find yourself adding an interface "in case we want to swap implementations later" — stop. Add it when there are
  two implementations.
- Comments explain *why*, not *what*. The code shows what.

### 1.3 Small, reviewable diffs

- One logical change per commit. One ticket per branch.
- If a change exceeds ~300 lines of meaningful diff, split it. Mechanical changes (renames, generated code) are exempt.
- Never mix refactoring and behavior changes in the same commit.

### 1.4 Plan before you code (for non-trivial work)

For anything beyond a few-line fix, post a short numbered plan in chat and wait for
confirmation before executing. The plan is the cheapest place to catch a wrong direction.

### 1.5 Working process: superpowers skills + adversarial review

Use the process tooling. If unsure whether a skill applies — invoke it (1% rule).

- **Per phase:** brainstorming for open-ended design, `test-driven-development` while coding,
  `systematic-debugging` for mysterious bugs, `writing-plans` + `executing-plans` for multi-step
  work, `verification-before-completion` before claiming done.
- **Adversarial review is mandatory before declaring a phase or PR complete.** Dispatch two
  subagents in parallel and brief them in **adversary mode** ("find every flaw, no benefit of
  the doubt"):
    - **Code review** — `feature-dev:code-reviewer` or `code-review:code-review`. Attack KISS /
      YAGNI violations, premature abstractions, scope creep, hidden complexity.
    - **Security review** — `security-review`. Assume malicious input; find every secret-leak
      path (logs, exception messages, process args, persisted state, cache, stack traces).
      Cross-check spec §11 line by line.
- **Use subagents for parallelism**, not just review (`Explore` for known targets,
  `general-purpose` for open-ended questions).
- A 60-second adversarial review on a 200-line diff catches what a 30-minute self-review misses.

---

## 2. Architecture rules (non-negotiable)

These derive from the spec. Violating them requires an explicit ADR-style discussion, not a one-line PR change.

### 2.1 Separation of concerns

```
ui/          ← Swing, tool window, settings page (depends on: service, notification)
service/     ← project-scoped state, orchestration  (depends on: cli, cache)
injection/   ← run-config adapters                  (depends on: service)
cli/         ← subprocess wrapper, JSON parsing     (depends on: nothing IntelliJ-specific*)
cache/       ← in-memory cache                      (depends on: nothing)
settings/    ← PersistentStateComponent             (depends on: nothing)
notification/← user-facing messages                 (depends on: nothing)
```

*Exception: `cli/` may use `GeneralCommandLine` from IntelliJ, but nothing else.

**Rules:**

- No `ui/` import in `service/`, `cli/`, `cache/`. Reverse is fine.
- No IntelliJ Platform import in `cli/` except `GeneralCommandLine` and `PathEnvironmentVariableUtil`.
- No `service/` import in `cli/` or `cache/`.
- `injection/core/` is platform-agnostic. Family-specific code lives in `injection/<family>/`.

If you find yourself wanting to violate a layer, the design is wrong. Stop and discuss.

### 2.2 The CLI is the only Doppler integration

- v1 makes **zero** direct HTTP calls to the Doppler API. Everything goes through `doppler` subprocess.
- If you find yourself reaching for `OkHttp`, `ktor-client`, or `HttpURLConnection` to talk to Doppler — stop. The
  answer is the CLI.

### 2.3 Run-config injection: core service stays family-free

- `DopplerProjectService.fetchSecrets()` returns `Map<String, String>`. That's the whole contract.
- Family-specific types stay in their own package:
    - `JavaParameters`, `JavaRunConfigurationBase` → `injection/java/`
    - `ExternalSystemRunConfiguration`, `GradleExecutionSettings`, `GradleExecutionContext` → `injection/gradle/`
    - `NodeTargetRun`, `AbstractNodeTargetRunProfile` → `injection/node/`
    - `AbstractPythonRunConfiguration` → `injection/python/`
- The shared pipeline (disposed-check → fetch → merge → apply → shadow-warn) lives in
  `injection/core/SecretInjectionRunner`. Each family-specific extension supplies its own
  `applyMerged: (Map<String, String>) -> Unit` callback.
- A new family adapter is a new package + a new optional `<depends config-file="...">` in `plugin.xml`, period.
  It does not touch the core service or `SecretInjectionRunner`.

### 2.4 Secrets are never persisted

This is enforced by code review, not by clever runtime checks. When reviewing your own diffs, ask:

- Did I write a secret value to a file? → No.
- Did I log a secret value? → No.
- Did I include a secret value in an exception message that gets logged? → No.
- Did I put a secret value in a CLI argument that shows up in `ps`? → No (use stdin).
- Did I include a secret value in a notification body? → No.
- Did I store a secret in `PersistentStateComponent`? → No.

If you can't answer "no" with certainty, the change is not ready.

---

## 3. Kotlin & code style

### 3.1 Language

- Kotlin 2.3+ (whatever the IntelliJ Platform Gradle plugin pins).
- JVM target: 17.
- Prefer Kotlin idioms (`data class`, `sealed class`, `when` exhaustiveness) over Java-style.
- Use `Result<T>` or sealed result types for fallible operations. Don't throw across module boundaries.

### 3.2 Style

- Follow IntelliJ Platform's Kotlin code style (built-in formatter). Static analysis runs via
  `./gradlew detekt` (config in `config/detekt/detekt.yml`). No ktlint.
- One top-level public class per file. Helpers and private classes can share a file when tightly coupled.

### 3.3 Naming

- Services: `DopplerXxxService` (e.g., `DopplerProjectService`).
- Settings: `DopplerXxxSettingsState`, `DopplerXxxConfigurable`.
- Extensions: `DopplerXxxRunConfigurationExtension`.
- Tests mirror production package structure with `Test` suffix.
- Avoid `Manager`, `Helper`, `Util` unless there's no better noun.

### 3.4 Nullability & errors

- Public APIs return non-null where possible. Use `Result` or sealed types for failure.
- Internal helpers can use nullable returns when the absence is meaningful.
- Never use `!!`. Use `requireNotNull` with an actionable message, or restructure.
- Don't catch `Throwable` or `Exception` broadly. Catch specifically.
- **Don't throw across module boundaries — except at the platform-mandated boundary.** Internally, fallible
  operations return `DopplerResult` / `Result`. The exception that crosses `service/` → `injection/` is mandated
  by IntelliJ: run-config hooks (`updateJavaParameters`, `patchCommandLine`, `addNodeOptionsTo`,
  `configureSettings`, …) abort a launch by *throwing* a `RuntimeException` — there is no `Result` return path.
    - **Exactly one throw-site:** `DopplerProjectService.fetchSecrets()`.
    - **Exactly one exception class:** `DopplerFetchException` (carries CLI stderr verbatim as `message`).
    - **Injectors rethrow bare** — never wrap in `ExecutionException` or any carrier. Wrapping smuggles the
      message into a `cause` chain that leaks via `e.toString()` / `e.stackTraceToString()` in third-party
      run-listeners and the IDE's Run console. The platform accepts any `RuntimeException` as a launch-abort
      signal, so a bare rethrow is sufficient.

### 3.5 Threading

- The EDT is sacred. Anything that shells out, hits the network, or does heavy computation runs on a background thread.
- Use IntelliJ's `Task.Backgroundable`, `ProgressManager`, or `AppExecutorUtil.getAppExecutorService()`.
- UI updates from a background thread go through `ApplicationManager.getApplication().invokeLater {}`.
- If you write `Thread { … }.start()`, you're doing it wrong.

---

## 4. IntelliJ Platform conventions

### 4.1 Services and lifecycle

- Use `@Service(Service.Level.PROJECT)` for project-scoped state.
- Use `@Service(Service.Level.APP)` only for stateless utilities (none in v1).
- Get services via `project.service<DopplerProjectService>()`, never `ServiceManager.getService()` (deprecated).
- Implement `Disposable` when holding listeners or subscriptions.

### 4.2 plugin.xml

- One `plugin.xml` per module. Family-specific extensions go behind `<depends optional="true" config-file="...">` so the
  plugin loads cleanly in IDEs without that family.
- Declare `<idea-version since-build="261"/>` (matches §9 of spec).
- Use `<localInspection>`, `<projectService>`, etc. — never inline Java-style XML registration.

### 4.3 Notifications

- One `NotificationGroup` registered in `plugin.xml`, ID = `Doppler`.
- All user-facing messages go through `DopplerNotifier`. Don't sprinkle `Notifications.Bus.notify` calls around the
  codebase.

### 4.4 Persistent state

- Anything in `PersistentStateComponent` is plain text in `.idea/`. Treat it as committed-and-public.
- No secret values, no tokens, no email addresses — just project/config names and booleans.

### 4.5 Resource bundles

- All user-facing strings go through `DopplerBundle.properties`. No hardcoded English in UI code.
- This costs ~zero up front and saves a translator's nightmare later.

---

## 5. Testing rules

### 5.1 What to test

- **Always:** every `cli/` method, every `cache/` method, every public `service/` method, every injector's merge logic.
- **Usually:** UI panels via headless Swing tests, settings persistence.
- **Don't:** generated code, trivial getters/setters, IntelliJ Platform classes themselves.

### 5.2 Test style

- One concept per test method. The name reads as a sentence: `secretCache_returnsCached_whenWithinTtl`.
- AAA structure: Arrange, Act, Assert. Blank lines between.
- Prefer `assertThat` (AssertJ via IntelliJ test helpers) over JUnit's `assertEquals` for readable failures.
- Tests use only fixture data. **Never** call the real Doppler API or CLI in tests. Use a fake CLI binary script for
  `DopplerCliClient` tests.

### 5.3 Test coverage

- No coverage threshold gate in CI. Coverage is a smell, not a goal.
- A PR that adds behavior without a test is incomplete. A PR that adds 20 tests for a one-line change is also
  incomplete / overkill. Use judgment.

---

## 6. Security checklist (run before every PR)

Mechanical, not optional. Check each item explicitly:

- [ ] No secret value appears in any `log.info`, `log.debug`, `log.warn`, `log.error`, `log.trace`.
- [ ] No secret value appears in any `Notification` body, title, or content.
- [ ] No secret value is written to disk (no temp files, no `.idea/`, no PSI cache).
- [ ] No secret value is passed as a CLI argument (use stdin via `GeneralCommandLine.withInput`).
- [ ] No secret value is included in an exception message that propagates to a logger.
- [ ] No Doppler token is read, written, or transmitted by plugin code.
- [ ] No new outbound network call to anything other than `doppler` subprocess.
- [ ] No new persistent state field that could hold a secret.
- [ ] All catch blocks log `e.message` only — never `e.toString()` of an exception that might wrap secret context.

If any answer is "I don't know," the PR is not ready.

---

## 7. Git & PR conventions

- You are not allowed to commit. You write, review code and work as copilot. The user is the one committing and pushing.
- If you are done with a change, write a commit message and a PR description, then ask the user to review and merge. Don't merge
  your own PRs.


### 8 Failure modes to actively resist

- **Cargo-cult complexity.** "I added a Strategy pattern for cache eviction policies" — no, there is one policy: TTL.
- **Premature abstraction.** "I made `SecretSource` an interface so we can plug in HashiCorp Vault later." No. Doppler
  today; Vault when there's a Vault ticket.
- **Silent scope creep.** Adding a feature flag, a settings option, or an extra command "while I'm in there." If it's
  not in the ticket, it's a separate PR.
- **Inventing IntelliJ APIs.** If you're not 100% sure an API exists with the signature you're writing — including
  extension point names, base class hierarchies, and `plugin.xml` element names — **stop and verify against the SDK
  docs (§0.1) or the IntelliJ Community source on GitHub**. Hallucinated APIs cost more time than asking. A wrong
  extension point name in `plugin.xml` produces obscure runtime failures.
- **Trusting "registered" as "invoked".** A platform extension point loading without errors does not mean its
  methods get called for the runtime path you care about (see §0.2 on `patchCommandLine` vs Gradle Tooling API).
  When in doubt, instrument with `thisLogger().info(...)` and verify in `idea.log`.


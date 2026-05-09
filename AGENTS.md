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

These are the URLs to consult before writing IntelliJ-specific code. Always check the live page — the SDK evolves and
your training data may be stale:

| Topic                                             | URL                                                                                      |
|---------------------------------------------------|------------------------------------------------------------------------------------------|
| **Quick Start (entry point)**                     | <https://plugins.jetbrains.com/docs/intellij/plugins-quick-start.html>                   |
| Developing a Plugin                               | <https://plugins.jetbrains.com/docs/intellij/developing-plugins.html>                    |
| Plugin Structure                                  | <https://plugins.jetbrains.com/docs/intellij/plugin-structure.html>                      |
| `plugin.xml` reference                            | <https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html>             |
| Implementing in Kotlin                            | <https://plugins.jetbrains.com/docs/intellij/using-kotlin.html>                          |
| **Run Configurations** (architecturally critical) | <https://plugins.jetbrains.com/docs/intellij/run-configurations.html>                    |
| Run Configurations Tutorial                       | <https://plugins.jetbrains.com/docs/intellij/run-configurations-tutorial.html>           |
| **IntelliJ Platform Gradle Plugin (2.x)**         | <https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html> |
| IntelliJ Platform Plugin Template                 | <https://github.com/JetBrains/intellij-platform-plugin-template>                         |
| Plugin Signing                                    | <https://plugins.jetbrains.com/docs/intellij/plugin-signing.html>                        |
| FAQ                                               | <https://plugins.jetbrains.com/docs/intellij/faq.html>                                   |
| Doppler CLI reference                             | <https://docs.doppler.com/reference/cli>                                                 |

### 0.2 Specific facts the SDK docs already settled (don't re-derive)

- **Gradle plugin ID is `org.jetbrains.intellij.platform`** (2.x). The 1.x `org.jetbrains.intellij` is frozen and must
  not be used.
- **Minimum Gradle 9.1, minimum JDK 21** for the 2.x Gradle plugin. This project is using gradle 9.2 and JDK 25, so we're good.
- **There is no single platform-wide "modify any run configuration" extension point.** Each family has its own subclass
  of `RunConfigurationExtensionBase`. Do not invent a generic hook.
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

For anything beyond a few-line fix, write a brief plan first as a chat message:

```
Plan:
1. Add SecretCache class with TTL-based eviction
2. Wire cache into DopplerProjectService.fetchSecrets()
3. Add unit tests for cache hit, miss, expiry
4. Wire manual invalidation to the refresh button
```

Wait for confirmation before executing. The plan is the cheapest place to catch a wrong direction.

### 1.5 Working process: superpowers skills + adversarial review

This codebase rewards discipline over speed. Default to using the available process tooling rather than improvising.

1. **Always invoke `superpowers:using-superpowers` at session start.** It's the gateway to the other process skills. If unsure whether a skill applies — invoke it. The 1% rule.

2. **Reach for the matching superpower before each phase, not after:**
    - Designing / open-ended scope → `superpowers:brainstorming`
    - Writing code → `superpowers:test-driven-development`
    - Stuck / mysterious bug → `superpowers:systematic-debugging`
    - Multi-step task → `superpowers:writing-plans` then `superpowers:executing-plans`
    - About to declare done → `superpowers:verification-before-completion`

3. **Adversarial review is mandatory before marking a phase or PR complete.** Dispatch two subagents in parallel:
    - **Code review** — `feature-dev:code-reviewer` agent or `code-review:code-review` skill. Brief explicitly in **adversary mode**: *"Find every flaw. No benefit of the doubt. Attack KISS / YAGNI violations, premature abstractions, leaking responsibilities, scope creep, hidden complexity. Prove this is wrong, not that it's right."*
    - **Security review** — `security-review` skill. Brief explicitly in **adversary mode**: *"Assume malicious input. Find every way a secret could leak via logs, exception messages, process args, persisted state, cache, telemetry, or stack traces. Cross-check spec §11 line by line."*
    - Adversary mode is **not balance**. Reviewer subagents default to charity; this codebase needs the opposite. The point is to surface flaws the author missed.

4. **Use subagents for parallelism**, not just review. When several independent reads / open-ended researches would otherwise bloat context, dispatch them concurrently via the `Agent` tool (`Explore` for known targets, `general-purpose` for open-ended questions).

5. **Don't skip review to "save time".** A 60-second adversarial review on a 200-line diff catches what a 30-minute self-review misses. Authors have blind spots — the review skills exist precisely because of that.

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
- Anything Java-specific (`JavaParameters`, `JavaRunConfigurationBase`) lives in `injection/java/`.
- Anything Gradle-specific lives in `injection/gradle/`.
- A new family adapter is a new package, period. It does not touch the core.

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

- Follow IntelliJ Platform's Kotlin code style (built-in formatter; run `./gradlew ktlintFormat` if configured).
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
- **Don't throw across module boundaries — except at the platform-mandated boundary.** The IntelliJ run-config extension contract (`updateJavaParameters`, Gradle `GradleRunConfigurationExtension.attachExtensionsToProcess`, etc.) aborts a launch by *throwing* a `RuntimeException` (typically `ExecutionException`) — there is no `Result` return path. We honour this with **exactly one** sanctioned boundary exception: `DopplerFetchException`, thrown by `DopplerProjectService.fetchSecrets()` and translated by injectors into `notifyError + rethrow`. The exception's `message` is the CLI's stderr verbatim — see the class doc for the (deliberately honest, not over-promising) contract. Everything *internal* to the service layer (CLI calls, cache, settings) returns `DopplerResult` / `Result`; the exception only crosses the `service/` → `injection/` boundary because the platform demands it.

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
- Declare `<idea-version since-build="242"/>` (matches §9 of spec).
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
- **Using deprecated APIs.** `ServiceManager.getService()`, `org.jetbrains.intellij` Gradle plugin (1.x), `intellij {}`
  extension block — all deprecated. Check the SDK docs before using anything that "feels like" the right API.


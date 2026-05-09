package com.tonihacks.doppler.service

/**
 * Thrown by [DopplerProjectService.fetchSecrets] when the underlying CLI call fails.
 *
 * **Why an exception (and not a `Result`/sealed type):**
 * run-config injectors must abort the launch on failure.
 * Throwing into the platform's `updateJavaParameters` / Gradle equivalent is the documented
 * IDE-style way to do that. Returning a `Result` and immediately rethrowing in every
 * injector would be the same shape with extra ceremony.
 *
 * **Message contract (HONEST version — do not strengthen without testing):**
 * The message is the Doppler CLI's `stderr.trim()` propagated verbatim from
 * `DopplerCliClient`. Today's Doppler CLI emits keys/slugs/error text on stderr but
 * **no value-bearing diagnostics** are formally guaranteed by Doppler. If a future
 * CLI release echoes a secret value into stderr (e.g. a "did you mean" hint that quotes
 * a sample value), this exception will leak it.
 *
 * Mitigations downstream:
 * - `DopplerNotifier` posts notifications with `isLogByDefault=false` (plugin.xml) — so
 *   the message fades with the balloon and never reaches the persistent Event Log.
 * - Injectors (Phase 7/8) must call `notifyError(project, e.message)` with the message
 *   and **never** `e.toString()` / `e.stackTraceToString()`.
 *
 * TODO (Phase 7/8): add a stderr line redactor in `cli/` that strips anything resembling
 * `KEY=VALUE` shapes before propagation, and tighten this contract.
 */
class DopplerFetchException(message: String) : RuntimeException(message)

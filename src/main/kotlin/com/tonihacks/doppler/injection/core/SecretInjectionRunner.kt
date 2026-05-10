package com.tonihacks.doppler.injection.core

import com.intellij.openapi.project.Project
import com.tonihacks.doppler.notification.DopplerNotifier
import com.tonihacks.doppler.service.DopplerFetchException
import com.tonihacks.doppler.service.DopplerProjectService

/**
 * Family-agnostic secret-injection pipeline. Encapsulates the fetch → merge → apply →
 * shadow-warn sequence shared by every run-config injector (Java, Gradle, Node.js,
 * Python). Each platform-specific injector owns:
 *   1. its `isApplicableFor` predicate (different config types)
 *   2. its `applyMerged` lambda (different ways to write env back: `JavaParameters.env`,
 *      `GeneralCommandLine.environment`, `NodeTargetRun.envData`, ...)
 *
 * Everything else — error policy, shadow notification, once-per-session deduplication —
 * lives here so the conflict policy is encoded in **one** place (cf. spec §2.3, §5.3,
 * §5.5, §11.7).
 *
 * **Failure policy (spec §5.5).** [DopplerProjectService.fetchSecrets] is the only
 * `throw` site in the service layer. The exception (`DopplerFetchException`) carries
 * CLI stderr verbatim as its `message`. We surface that message via [notifyError] and
 * **rethrow `e` directly** — never wrap. Wrapping would smuggle the verbatim message
 * into a `cause` chain that other run-listeners log via `e.toString()`. The platform
 * accepts any `RuntimeException` from the extension hook as a launch-abort signal.
 *
 * **Shadow notification dedup (spec §5.3).** When local run-config env vars shadow
 * Doppler-managed keys, we emit a one-time-per-session balloon listing the *keys*
 * (never values — spec §11.7). Dedup is keyed by `configName` via [OverrideTracker].
 */
internal object SecretInjectionRunner {

    fun run(
        project: Project,
        existingEnv: Map<String, String>,
        configName: String,
        service: DopplerProjectService,
        applyMerged: (Map<String, String>) -> Unit,
        notifyError: (Project, String) -> Unit = DopplerNotifier::notifyError,
        notifyWarning: (Project, String) -> Unit = DopplerNotifier::notifyWarning,
    ) {
        val secrets = try {
            service.fetchSecrets()
        } catch (e: DopplerFetchException) {
            notifyError(project, checkNotNull(e.message))
            throw e
        }

        if (secrets.isEmpty()) return

        val result = SecretMerger.merge(existingEnv, secrets)
        applyMerged(result.merged)

        if (result.shadowedKeys.isNotEmpty()) {
            val tracker = OverrideTracker.getInstance(project)
            if (tracker.markReportedIfNew(configName)) {
                val keys = result.shadowedKeys.sorted().joinToString(", ")
                notifyWarning(
                    project,
                    "${result.shadowedKeys.size} Doppler-managed env var(s) are shadowed by " +
                        "local values in `$configName`: $keys.",
                )
            }
        }
    }
}

package com.tonihacks.doppler.injection.core

import com.intellij.openapi.project.Project
import com.tonihacks.doppler.notification.DopplerNotifier
import com.tonihacks.doppler.service.DopplerFetchException
import com.tonihacks.doppler.service.DopplerProjectService

/**
 * Family-agnostic injection pipeline (fetch → merge → apply → shadow-warn).
 * Each platform-specific injector supplies its own `applyMerged` (writes env back
 * onto `JavaParameters`, `GeneralCommandLine`, `NodeTargetRun`, ...).
 *
 * On CLI failure the pipeline rethrows [DopplerFetchException] bare — wrapping in
 * `ExecutionException` would smuggle stderr into a cause chain that listeners log.
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

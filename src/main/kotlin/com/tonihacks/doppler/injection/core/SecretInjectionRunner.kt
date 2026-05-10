package com.tonihacks.doppler.injection.core

import com.intellij.openapi.diagnostic.thisLogger
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
        // Node's createLaunchSession → addNodeOptionsTo path can fire after the project
        // is closed. Service / tracker lookup against a disposed project throws
        // AlreadyDisposedException — which the platform may surface as a "Report to
        // JetBrains" dialog rather than a clean launch-abort. No injection is the
        // correct outcome when the project is gone.
        val log = thisLogger()
        log.info("[doppler-debug] SecretInjectionRunner.run entry: configName='$configName' existingEnvSize=${existingEnv.size} disposed=${project.isDisposed}")
        if (project.isDisposed) return

        val secrets = try {
            service.fetchSecrets()
        } catch (e: DopplerFetchException) {
            log.info("[doppler-debug] fetchSecrets threw DopplerFetchException for '$configName'")
            notifyError(project, checkNotNull(e.message))
            throw e
        }

        log.info("[doppler-debug] fetchSecrets returned ${secrets.size} keys for '$configName'")
        if (secrets.isEmpty()) return

        val result = SecretMerger.merge(existingEnv, secrets)
        log.info("[doppler-debug] applyMerged for '$configName': mergedSize=${result.merged.size} shadowed=${result.shadowedKeys.size}")
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

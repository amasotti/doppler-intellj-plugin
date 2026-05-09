package com.tonihacks.doppler.injection.gradle

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.externalSystem.service.execution.configuration.ExternalSystemRunConfigurationExtension
import com.intellij.openapi.project.Project
import com.tonihacks.doppler.injection.core.OverrideTracker
import com.tonihacks.doppler.injection.core.SecretMerger
import com.tonihacks.doppler.notification.DopplerNotifier
import com.tonihacks.doppler.service.DopplerFetchException
import com.tonihacks.doppler.service.DopplerProjectService
import org.jetbrains.plugins.gradle.util.GradleConstants

/**
 * Injects Doppler-managed secrets into Gradle run configurations before process launch.
 *
 * Registered as an optional extension — only active when the Gradle plugin is present
 * (`com.intellij.gradle`). Loaded via `META-INF/doppler-gradle.xml`.
 *
 * **Injection path (spec §5.4):** [patchCommandLine] runs on a background thread (the
 * process-creation path). It delegates to [injectSecrets] which calls
 * [DopplerProjectService.fetchSecrets] (cache-first), merges with `config.settings.env`
 * (local wins — spec §5.3), and writes the result into [GeneralCommandLine.environment]
 * before Gradle starts.
 *
 * **Failure policy (spec §5.5):** CLI errors throw [DopplerFetchException]. The exception
 * is surfaced as a balloon notification and rethrown — the launch is aborted. Never
 * silently launching without secrets is a hard requirement.
 *
 * **Shadow notification (spec §8.3):** when local run-config env vars shadow Doppler-managed
 * keys, a one-time-per-session balloon warning lists the *keys* (never values — spec §11.7).
 * Deduplication is handled by [OverrideTracker].
 */
class DopplerGradleRunConfigurationExtension : ExternalSystemRunConfigurationExtension() {

    override fun isApplicableFor(configuration: ExternalSystemRunConfiguration): Boolean =
        configuration.settings.externalSystemId == GradleConstants.SYSTEM_ID

    override fun patchCommandLine(
        configuration: ExternalSystemRunConfiguration,
        runnerSettings: RunnerSettings?,
        cmdLine: GeneralCommandLine,
        runnerId: String,
    ) {
        val project = configuration.project
        injectSecrets(
            project = project,
            existingEnv = configuration.settings.env,
            configName = configuration.name,
            cmdLine = cmdLine,
            service = DopplerProjectService.getInstance(project),
        )
    }

    /**
     * Core injection logic, extracted as `internal` for unit testing without a live
     * run-configuration context. [patchCommandLine] is the single production call-site.
     *
     * @param existingEnv the run config's user-set env vars (not system env) — these win
     *   over Doppler values on collision (spec §5.3 "local wins").
     * @param service caller-supplied so tests can inject a fake without touching the
     *   service container.
     */
    internal fun injectSecrets(
        project: Project,
        existingEnv: Map<String, String>,
        configName: String,
        cmdLine: GeneralCommandLine,
        service: DopplerProjectService,
    ) {
        val secrets = try {
            service.fetchSecrets()
        } catch (e: DopplerFetchException) {
            // e.message is CLI stderr verbatim — do NOT log it (spec §6.2 / §11.2).
            // DopplerNotifier posts BALLOON with isLogByDefault=false so it fades, never persists.
            DopplerNotifier.notifyError(project, e.message ?: "Doppler fetch failed")
            throw e
        }

        if (secrets.isEmpty()) return

        val result = SecretMerger.merge(existingEnv, secrets)
        cmdLine.withEnvironment(result.merged)

        if (result.shadowedKeys.isNotEmpty()) {
            val tracker = OverrideTracker.getInstance(project)
            if (tracker.markReportedIfNew(configName)) {
                val keys = result.shadowedKeys.sorted().joinToString(", ")
                DopplerNotifier.notifyWarning(
                    project,
                    "${result.shadowedKeys.size} Doppler-managed env var(s) are shadowed by " +
                        "local values in `$configName`: $keys.",
                )
            }
        }
    }
}

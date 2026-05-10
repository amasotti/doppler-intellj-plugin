package com.tonihacks.doppler.injection.gradle

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.externalSystem.service.execution.configuration.ExternalSystemRunConfigurationExtension
import com.intellij.openapi.project.Project
import com.tonihacks.doppler.injection.core.SecretInjectionRunner
import com.tonihacks.doppler.notification.DopplerNotifier
import com.tonihacks.doppler.service.DopplerProjectService
import org.jetbrains.plugins.gradle.util.GradleConstants

/**
 * Injects Doppler-managed secrets into Gradle run configurations before process launch.
 *
 * Registered as an optional extension — only active when the Gradle plugin is present
 * (`com.intellij.gradle`). Loaded via `META-INF/doppler-gradle.xml`.
 *
 * **Injection path (spec §5.4):** [patchCommandLine] runs on a background thread (the
 * process-creation path). It delegates to [SecretInjectionRunner] which calls
 * [DopplerProjectService.fetchSecrets] (cache-first), merges with `config.settings.env`
 * (local wins — spec §5.3), and writes the result into [GeneralCommandLine.environment]
 * before Gradle starts.
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
        injectSecrets(
            project = configuration.project,
            existingEnv = configuration.settings.env,
            configName = configuration.name,
            cmdLine = cmdLine,
            service = DopplerProjectService.getInstance(configuration.project),
        )
    }

    /**
     * Testable seam: thin wrapper over [SecretInjectionRunner.run] that captures the
     * Gradle-specific `applyMerged` step (writing back to [GeneralCommandLine.environment]).
     * Tests call this directly with a fake [service] and overridden notification callbacks.
     */
    internal fun injectSecrets(
        project: Project,
        existingEnv: Map<String, String>,
        configName: String,
        cmdLine: GeneralCommandLine,
        service: DopplerProjectService,
        notifyError: (Project, String) -> Unit = DopplerNotifier::notifyError,
        notifyWarning: (Project, String) -> Unit = DopplerNotifier::notifyWarning,
    ) {
        SecretInjectionRunner.run(
            project = project,
            existingEnv = existingEnv,
            configName = configName,
            service = service,
            applyMerged = { merged -> cmdLine.withEnvironment(merged) },
            notifyError = notifyError,
            notifyWarning = notifyWarning,
        )
    }
}

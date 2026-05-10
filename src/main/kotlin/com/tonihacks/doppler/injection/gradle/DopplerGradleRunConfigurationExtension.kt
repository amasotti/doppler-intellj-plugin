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

/** Gradle injector. Loaded via `doppler-gradle.xml`. Filters out Maven/npm by SYSTEM_ID. */
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

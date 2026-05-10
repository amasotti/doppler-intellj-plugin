package com.tonihacks.doppler.injection.java

import com.intellij.execution.JavaRunConfigurationBase
import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.project.Project
import com.tonihacks.doppler.injection.core.SecretInjectionRunner
import com.tonihacks.doppler.notification.DopplerNotifier
import com.tonihacks.doppler.service.DopplerProjectService

/** JVM injector — Application (Java/Kotlin), JUnit, TestNG, Spring Boot. Loaded via `doppler-java.xml`. */
class DopplerJavaRunConfigurationExtension : RunConfigurationExtension() {

    override fun isApplicableFor(config: RunConfigurationBase<*>): Boolean =
        config is JavaRunConfigurationBase

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        injectSecrets(
            project = configuration.project,
            existingEnv = params.env.toMap(),
            configName = configuration.name,
            params = params,
            service = DopplerProjectService.getInstance(configuration.project),
        )
    }

    internal fun injectSecrets(
        project: Project,
        existingEnv: Map<String, String>,
        configName: String,
        params: JavaParameters,
        service: DopplerProjectService,
        notifyError: (Project, String) -> Unit = DopplerNotifier::notifyError,
        notifyWarning: (Project, String) -> Unit = DopplerNotifier::notifyWarning,
    ) {
        SecretInjectionRunner.run(
            project = project,
            existingEnv = existingEnv,
            configName = configName,
            service = service,
            applyMerged = { merged -> params.env = HashMap(merged) },
            notifyError = notifyError,
            notifyWarning = notifyWarning,
        )
    }
}

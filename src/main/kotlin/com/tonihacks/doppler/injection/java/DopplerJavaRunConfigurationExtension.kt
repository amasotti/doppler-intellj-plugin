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

/**
 * Injects Doppler-managed secrets into JVM run configurations before process launch.
 *
 * Covers all run configuration types that build a [JavaParameters] object:
 * Application (Java + Kotlin), JUnit, TestNG, Spring Boot Application. The
 * single [updateJavaParameters] hook is shared across all of them — the
 * IntelliJ platform routes every JVM launch through it.
 *
 * Registered as an optional extension — only active when the Java plugin is
 * present (`com.intellij.java`). Loaded via `META-INF/doppler-java.xml`.
 *
 * All cross-family logic (fetch, merge, shadow-warn, error policy) lives in
 * [SecretInjectionRunner]. This class only owns the JVM-specific predicate and
 * the `applyMerged` lambda that writes back to [JavaParameters.env].
 */
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
            existingEnv = params.env.toMap(), // snapshot before mutation
            configName = configuration.name,
            params = params,
            service = DopplerProjectService.getInstance(configuration.project),
        )
    }

    /**
     * Testable seam: thin wrapper over [SecretInjectionRunner.run] that captures the
     * Java-specific `applyMerged` step (writing back to [JavaParameters.env]). Tests
     * call this directly with a fake [service] and overridden notification callbacks.
     */
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

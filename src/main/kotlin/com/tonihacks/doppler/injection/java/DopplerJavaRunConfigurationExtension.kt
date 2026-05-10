package com.tonihacks.doppler.injection.java

import com.intellij.execution.JavaRunConfigurationBase
import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.project.Project
import com.tonihacks.doppler.injection.core.OverrideTracker
import com.tonihacks.doppler.injection.core.SecretMerger
import com.tonihacks.doppler.notification.DopplerNotifier
import com.tonihacks.doppler.service.DopplerFetchException
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
 */
class DopplerJavaRunConfigurationExtension : RunConfigurationExtension() {

    override fun isApplicableFor(config: RunConfigurationBase<*>): Boolean =
        config is JavaRunConfigurationBase

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        val project = configuration.project
        injectSecrets(
            project = project,
            existingEnv = params.env.toMap(), // snapshot before mutation
            configName = configuration.name,
            params = params,
            service = DopplerProjectService.getInstance(project),
        )
    }

    /**
     * Core injection logic, extracted as `internal` for unit testing without a live
     * run-configuration context. [updateJavaParameters] is the single production call-site.
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
        val secrets = try {
            service.fetchSecrets()
        } catch (e: DopplerFetchException) {
            notifyError(project, checkNotNull(e.message))
            throw e
        }

        if (secrets.isEmpty()) return

        val result = SecretMerger.merge(existingEnv, secrets)
        params.env = HashMap(result.merged)

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

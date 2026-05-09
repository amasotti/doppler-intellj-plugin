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
 *
 * **Injection path (spec §5.4):** [updateJavaParameters] runs on the process-
 * creation path. It delegates to [injectSecrets] which calls
 * [DopplerProjectService.fetchSecrets] (cache-first), merges with the existing
 * `params.env` snapshot (local wins — spec §5.3), then replaces `params.env`
 * with the merged result via [JavaParameters.setEnv].
 *
 * **Failure policy (spec §5.5):** CLI errors throw [DopplerFetchException].
 * The exception is surfaced as a balloon notification and rethrown — the launch
 * is aborted. Never silently launching without secrets is a hard requirement.
 *
 * **Shadow notification (spec §8.3):** when local run-config env vars shadow
 * Doppler-managed keys, a one-time-per-session balloon warning lists the *keys*
 * (never values — spec §11.7). Deduplication is handled by [OverrideTracker].
 */
class DopplerJavaRunConfigurationExtension : RunConfigurationExtension() {

    // Filter to JVM-family configs only. RunConfigurationExtension also guards
    // readExternal/writeExternal/validateConfiguration/createEditor — returning true
    // unconditionally attaches all those hooks to every config type (Gradle, Shell, Docker).
    // JavaRunConfigurationBase is the common parent for Application, JUnit, TestNG,
    // Spring Boot Application, and Kotlin run configurations.
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
     *
     * @param existingEnv the run config's current env map snapshot (local overrides win
     *   over Doppler values on collision — spec §5.3 "local wins"). In production this is
     *   `params.env.toMap()` taken before mutation; in tests it is supplied directly.
     * @param service caller-supplied so tests can inject a fake without touching the
     *   service container.
     * @param notifyError overridable in tests to capture notification calls without MockK.
     * @param notifyWarning overridable in tests to capture notification calls without MockK.
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
            // e.message is CLI stderr verbatim — do NOT log it (spec §6.2 / §11.2).
            // DopplerNotifier posts BALLOON with isLogByDefault=false so it fades, never persists.
            notifyError(project, checkNotNull(e.message))
            throw e
        }

        if (secrets.isEmpty()) return

        val result = SecretMerger.merge(existingEnv, secrets)
        // Copy out of the redactedView proxy into a plain HashMap before handing to
        // JavaParameters.setEnv — some platform paths attempt mutation after the fact,
        // and the proxy (delegating Map<String,String>, not MutableMap) may throw on
        // those calls. HashMap is the type the platform expects internally.
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

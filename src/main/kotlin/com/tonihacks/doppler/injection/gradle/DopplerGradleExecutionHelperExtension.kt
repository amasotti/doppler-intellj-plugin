package com.tonihacks.doppler.injection.gradle

import com.intellij.openapi.diagnostic.thisLogger
import com.tonihacks.doppler.injection.core.SecretInjectionRunner
import com.tonihacks.doppler.service.DopplerProjectService
import org.jetbrains.plugins.gradle.service.execution.GradleExecutionContext
import org.jetbrains.plugins.gradle.service.project.GradleExecutionHelperExtension
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings

/**
 * Gradle injector for the Tooling-API execution path.
 *
 * The legacy [DopplerGradleRunConfigurationExtension.patchCommandLine] hook never fires
 * for IDEA's default "Build and run using: Gradle" delegation — Gradle launches via the
 * Tooling API and never builds a [com.intellij.execution.configurations.GeneralCommandLine]
 * for the user task. The Tooling-API path reads env from
 * [GradleExecutionSettings.getEnv] (see `GradleExecutionHelper.setupEnvironment`), so the
 * supported mutation point is [configureSettings] on this extension.
 *
 * Registered under EP `org.jetbrains.plugins.gradle.executionHelperExtension`.
 */
class DopplerGradleExecutionHelperExtension : GradleExecutionHelperExtension {

    override fun configureSettings(settings: GradleExecutionSettings, context: GradleExecutionContext) {
        // The context does not expose a run-config name. Use a stable per-project,
        // per-task-set key so the shadow-warning dedup (OverrideTracker) fires once
        // per logical "run" rather than once per launch.
        val configName = "${context.projectPath}:${settings.tasks.joinToString(" ")}"
        thisLogger().info(
            "[doppler-debug] Gradle configureSettings fired key='$configName' " +
                "existingEnvSize=${settings.env.size} taskCount=${settings.tasks.size}",
        )
        SecretInjectionRunner.run(
            project = context.project,
            existingEnv = settings.env,
            configName = configName,
            service = DopplerProjectService.getInstance(context.project),
            // withEnvironmentVariables does putAll — local entries are unchanged
            // (merged already preserves them per local-wins) and Doppler-only keys
            // get added.
            applyMerged = { merged -> settings.withEnvironmentVariables(merged) },
        )
    }
}

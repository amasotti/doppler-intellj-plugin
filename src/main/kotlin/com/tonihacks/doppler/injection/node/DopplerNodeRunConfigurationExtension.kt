package com.tonihacks.doppler.injection.node

import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.execution.runConfiguration.AbstractNodeRunConfigurationExtension
import com.intellij.javascript.nodejs.execution.runConfiguration.NodeRunConfigurationLaunchSession
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.tonihacks.doppler.injection.core.SecretInjectionRunner
import com.tonihacks.doppler.service.DopplerProjectService

/**
 * Node.js / npm / yarn / pnpm / Jest / Vitest injector. Loaded via `doppler-node.xml`.
 *
 * `patchCommandLine` is `protected final` on the Node base, so env injection happens
 * via [createLaunchSession] → [NodeRunConfigurationLaunchSession.addNodeOptionsTo],
 * which fires after env init but before process spawn.
 */
class DopplerNodeRunConfigurationExtension : AbstractNodeRunConfigurationExtension() {

    override fun isApplicableFor(profile: AbstractNodeTargetRunProfile): Boolean = true

    override fun createLaunchSession(
        configuration: AbstractNodeTargetRunProfile,
        environment: ExecutionEnvironment,
    ): NodeRunConfigurationLaunchSession {
        thisLogger().info("[doppler-debug] Node createLaunchSession fired for '${configuration.name}'")
        return DopplerNodeLaunchSession(configuration.project, configuration.name)
    }

    private class DopplerNodeLaunchSession(
        private val project: Project,
        private val configName: String,
    ) : NodeRunConfigurationLaunchSession() {

        override fun addNodeOptionsTo(targetRun: NodeTargetRun) {
            SecretInjectionRunner.run(
                project = project,
                existingEnv = targetRun.envData.envs,
                configName = configName,
                service = DopplerProjectService.getInstance(project),
                // .with(map) replaces envs while preserving passParentEnvs and environmentFile.
                applyMerged = { merged -> targetRun.envData = targetRun.envData.with(merged) },
            )
        }
    }
}

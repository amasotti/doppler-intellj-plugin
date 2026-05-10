package com.tonihacks.doppler.injection.node

import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.execution.runConfiguration.AbstractNodeRunConfigurationExtension
import com.intellij.javascript.nodejs.execution.runConfiguration.NodeRunConfigurationLaunchSession
import com.intellij.openapi.project.Project
import com.tonihacks.doppler.injection.core.SecretInjectionRunner
import com.tonihacks.doppler.service.DopplerProjectService

/**
 * Injects Doppler-managed secrets into Node.js / npm / yarn / pnpm / Jest / Vitest
 * run configurations before process launch.
 *
 * Registered as an optional extension — only active when the JavaScript plugin is
 * present (`JavaScript`). Loaded via `META-INF/doppler-node.xml`.
 *
 * **Why not [patchCommandLine] like the JVM injector?** [AbstractNodeRunConfigurationExtension.patchCommandLine]
 * is `protected final` in the platform — Node-internal extensions own that hook for
 * editor-tab settings (`--require`, profiling args, etc.). The platform exposes a
 * separate, equally-final pre-launch hook for env mutation: [createLaunchSession]
 * returns a [NodeRunConfigurationLaunchSession] whose [NodeRunConfigurationLaunchSession.addNodeOptionsTo]
 * fires after env initialization but before the process spawns. We mutate
 * [NodeTargetRun.envData] there.
 *
 * **Replacement semantics.** `EnvironmentVariablesData.with(Map)` returns a new
 * value with the given map fully replacing the envs (preserving `passParentEnvs` and
 * `environmentFile`). [SecretInjectionRunner] hands us the already-merged map
 * (Doppler ∪ existing, local-wins on collision), so a full replacement is exactly
 * what we want.
 */
class DopplerNodeRunConfigurationExtension : AbstractNodeRunConfigurationExtension() {

    override fun isApplicableFor(profile: AbstractNodeTargetRunProfile): Boolean = true

    override fun createLaunchSession(
        configuration: AbstractNodeTargetRunProfile,
        environment: ExecutionEnvironment,
    ): NodeRunConfigurationLaunchSession = DopplerNodeLaunchSession(configuration.project, configuration.name)

    /**
     * Per-launch session that injects secrets into [NodeTargetRun] env data right
     * before the Node process starts.
     */
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
                applyMerged = { merged -> targetRun.envData = targetRun.envData.with(merged) },
            )
        }
    }
}

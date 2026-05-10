package com.tonihacks.doppler.injection.python

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.jetbrains.python.run.AbstractPythonRunConfiguration
import com.jetbrains.python.run.PythonRunConfigurationExtension
import com.tonihacks.doppler.injection.core.SecretInjectionRunner
import com.tonihacks.doppler.service.DopplerProjectService

/**
 * Python injector — script, pytest, unittest, Django manage.py, Flask, FastAPI.
 * Loaded via `doppler-python.xml`. EP namespace is historical (`Pythonid`) but the
 * plugin id is `PythonCore`.
 */
class DopplerPythonRunConfigurationExtension : PythonRunConfigurationExtension() {

    override fun isApplicableFor(configuration: AbstractPythonRunConfiguration<*>): Boolean = true

    override fun isEnabledFor(
        applicableConfiguration: AbstractPythonRunConfiguration<*>,
        runnerSettings: RunnerSettings?,
    ): Boolean = true

    override fun patchCommandLine(
        configuration: AbstractPythonRunConfiguration<*>,
        runnerSettings: RunnerSettings?,
        cmdLine: GeneralCommandLine,
        runnerId: String,
    ) {
        SecretInjectionRunner.run(
            project = configuration.project,
            existingEnv = cmdLine.environment.toMap(),
            configName = configuration.name,
            service = DopplerProjectService.getInstance(configuration.project),
            applyMerged = { merged -> cmdLine.withEnvironment(merged) },
        )
    }
}

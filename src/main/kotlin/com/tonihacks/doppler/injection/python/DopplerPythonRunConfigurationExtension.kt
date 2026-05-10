package com.tonihacks.doppler.injection.python

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.jetbrains.python.run.AbstractPythonRunConfiguration
import com.jetbrains.python.run.PythonRunConfigurationExtension
import com.tonihacks.doppler.injection.core.SecretInjectionRunner
import com.tonihacks.doppler.service.DopplerProjectService

/**
 * Injects Doppler-managed secrets into Python run configurations before process launch.
 *
 * Covers all run config types whose state extends `AbstractPythonRunConfiguration`:
 * Python script, pytest, unittest, Django manage.py, Flask, FastAPI, etc. The
 * platform routes every Python launch through `PythonCommandLineState`, which calls
 * `PythonRunConfigurationExtensionsManager.patchCommandLine` — that fires the
 * [patchCommandLine] hook below for each registered extension.
 *
 * Registered as an optional extension — only active when the Python plugin is present
 * (`PythonCore`). Loaded via `META-INF/doppler-python.xml`.
 *
 * Note on plugin id: the EP namespace is historical (`Pythonid.runConfigurationExtension`);
 * the modern plugin id is `PythonCore` for community + IDEA Ultimate's bundled Python
 * support, with `Pythonid` reserved for Pro features that depend on `PythonCore`.
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
            existingEnv = cmdLine.environment.toMap(), // snapshot before mutation
            configName = configuration.name,
            service = DopplerProjectService.getInstance(configuration.project),
            applyMerged = { merged -> cmdLine.withEnvironment(merged) },
        )
    }
}

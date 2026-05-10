package com.tonihacks.doppler.injection.python

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.tonihacks.doppler.cli.DopplerCliClient
import com.tonihacks.doppler.service.DopplerProjectService
import com.tonihacks.doppler.settings.DopplerSettingsState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Note: there is no `isApplicableFor` test against a real `PythonRunConfiguration` here.
 * `@TestApplication` does not register `PythonConfigurationType` in the test harness
 * even with `plugin("PythonCore", ...)` on the classpath. The `patchCommandLine` lambda
 * contract is exercised via [com.tonihacks.doppler.injection.core.SecretInjectionRunner];
 * end-to-end behavior is covered by `./gradlew runPyCharm` smoke testing.
 */
@TestApplication
class DopplerPythonRunConfigurationExtensionTest {

    private val projectFixture = projectFixture()

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun resetSettings() {
        DopplerSettingsState.getInstance(projectFixture.get())
            .loadState(DopplerSettingsState.State())
    }

    private fun configureSettings() {
        val s = DopplerSettingsState.getInstance(projectFixture.get()).state
        s.enabled = true
        s.dopplerProject = "test-proj"
        s.dopplerConfig = "dev"
    }

    private fun jsonCli(json: String): DopplerCliClient {
        val jsonFile = File.createTempFile("fixture-", ".json", tempDir)
        jsonFile.writeText(json)
        val script = File.createTempFile("doppler-", ".sh", tempDir)
        script.writeText("#!/bin/sh\ncat '${jsonFile.absolutePath}'\n")
        script.setExecutable(true)
        return DopplerCliClient(script.absolutePath)
    }

    private fun makeService(cli: DopplerCliClient): DopplerProjectService =
        DopplerProjectService(projectFixture.get()) { cli }

    @Test
    fun `applyMerged writes Doppler secrets into command line environment`() {
        configureSettings()
        val cmdLine = GeneralCommandLine()
        // SecretInjectionRunner is internal to the same module; call the platform hook
        // by going through DopplerProjectService.replace via constructor injection in
        // the runner is not reachable. Instead invoke the runner directly with the
        // injector's apply lambda — same logic the production patchCommandLine runs.
        com.tonihacks.doppler.injection.core.SecretInjectionRunner.run(
            project = projectFixture.get(),
            existingEnv = cmdLine.environment.toMap(),
            configName = "py-test",
            service = makeService(jsonCli("""{"FAKE_PY_SECRET":"py-fake"}""")),
            applyMerged = { merged -> cmdLine.withEnvironment(merged) },
        )
        assertThat(cmdLine.environment).containsEntry("FAKE_PY_SECRET", "py-fake")
    }

    @Test
    fun `applyMerged respects local-wins on collision`() {
        configureSettings()
        val cmdLine = GeneralCommandLine()
        cmdLine.withEnvironment("FAKE_PY_KEY", "local-val")
        com.tonihacks.doppler.injection.core.SecretInjectionRunner.run(
            project = projectFixture.get(),
            existingEnv = cmdLine.environment.toMap(),
            configName = "py-collision",
            service = makeService(jsonCli("""{"FAKE_PY_KEY":"doppler-val","FAKE_EXTRA":"xyz"}""")),
            applyMerged = { merged -> cmdLine.withEnvironment(merged) },
        )
        assertThat(cmdLine.environment)
            .containsEntry("FAKE_PY_KEY", "local-val")
            .containsEntry("FAKE_EXTRA", "xyz")
    }

}

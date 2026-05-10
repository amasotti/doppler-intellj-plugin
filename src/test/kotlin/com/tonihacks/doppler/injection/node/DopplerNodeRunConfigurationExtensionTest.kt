package com.tonihacks.doppler.injection.node

import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.tonihacks.doppler.cli.DopplerCliClient
import com.tonihacks.doppler.injection.core.SecretInjectionRunner
import com.tonihacks.doppler.service.DopplerProjectService
import com.tonihacks.doppler.settings.DopplerSettingsState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Note: there is no `isApplicableFor` test for a real `NodeJsRunConfiguration` here.
 * `@TestApplication` does not activate the NodeJS plugin's run-config types in the
 * test harness — `NodeJsRunConfigurationType.getInstance()` throws because the type
 * is not registered, even with `bundledPlugin("NodeJS")` on the classpath. The
 * `addNodeOptionsTo` lambda contract is exercised here via [SecretInjectionRunner],
 * and end-to-end behavior is covered by `./gradlew runWebStorm` smoke testing.
 */
@TestApplication
class DopplerNodeRunConfigurationExtensionTest {

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
    fun `EnvironmentVariablesData with(merged) does not mutate the original instance`() {
        // Critical security invariant: the launch session writes back via
        // `targetRun.envData = targetRun.envData.with(merged)`. The risk would be that
        // `with()` mutates in place, making the configuration's persisted envData
        // contain Doppler secrets after launch (→ workspace.xml leak on save).
        // EnvironmentVariablesData is immutable; `with()` returns a new instance —
        // verified here.
        val original = EnvironmentVariablesData.create(mapOf("LOCAL" to "loc"), true)
        val updated = original.with(mapOf("LOCAL" to "loc", "DOPPLER" to "dop"))

        assertThat(original.envs).isEqualTo(mapOf("LOCAL" to "loc"))
        assertThat(updated.envs).isEqualTo(mapOf("LOCAL" to "loc", "DOPPLER" to "dop"))
        assertThat(original).isNotSameAs(updated)
    }

    @Test
    fun `runner applies merged env to a captured target via lambda`() {
        // Stand-in for the addNodeOptionsTo path: the production launch session
        // builds a fresh EnvironmentVariablesData and writes it back via setEnvData.
        // We mirror that contract with a captured var rather than constructing a
        // NodeTargetRun (which requires a live Node interpreter setup).
        configureSettings()
        var written: EnvironmentVariablesData = EnvironmentVariablesData.create(emptyMap(), true)
        SecretInjectionRunner.run(
            project = projectFixture.get(),
            existingEnv = written.envs,
            configName = "node-test",
            service = makeService(jsonCli("""{"FAKE_NODE_SECRET":"node-fake"}""")),
            applyMerged = { merged -> written = written.with(merged) },
        )
        assertThat(written.envs).containsEntry("FAKE_NODE_SECRET", "node-fake")
    }

    @Test
    fun `runner respects local-wins on Node-style env collision`() {
        configureSettings()
        var written: EnvironmentVariablesData =
            EnvironmentVariablesData.create(mapOf("FAKE_NODE_KEY" to "local-val"), true)
        SecretInjectionRunner.run(
            project = projectFixture.get(),
            existingEnv = written.envs,
            configName = "node-collision",
            service = makeService(jsonCli("""{"FAKE_NODE_KEY":"doppler-val","FAKE_EXTRA":"xyz"}""")),
            applyMerged = { merged -> written = written.with(merged) },
        )
        assertThat(written.envs)
            .containsEntry("FAKE_NODE_KEY", "local-val")
            .containsEntry("FAKE_EXTRA", "xyz")
    }
}

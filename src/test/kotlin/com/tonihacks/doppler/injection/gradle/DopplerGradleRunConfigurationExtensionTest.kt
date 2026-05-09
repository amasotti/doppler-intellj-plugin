package com.tonihacks.doppler.injection.gradle

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.tonihacks.doppler.cli.DopplerCliClient
import com.tonihacks.doppler.service.DopplerFetchException
import com.tonihacks.doppler.service.DopplerProjectService
import com.tonihacks.doppler.settings.DopplerSettingsState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@TestApplication
class DopplerGradleRunConfigurationExtensionTest {

    private val projectFixture = projectFixture()
    private val ext = DopplerGradleRunConfigurationExtension()

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun resetSettings() {
        DopplerSettingsState.getInstance(projectFixture.get())
            .loadState(DopplerSettingsState.State())
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun configureSettings(
        enabled: Boolean = true,
        dopplerProject: String = "test-proj",
        dopplerConfig: String = "dev",
    ) {
        val s = DopplerSettingsState.getInstance(projectFixture.get()).state
        s.enabled = enabled
        s.dopplerProject = dopplerProject
        s.dopplerConfig = dopplerConfig
    }

    private fun fakeCli(script: String): DopplerCliClient {
        val f = File.createTempFile("doppler-", ".sh", tempDir)
        f.writeText("#!/bin/sh\n$script\n")
        f.setExecutable(true)
        return DopplerCliClient(f.absolutePath)
    }

    /** CLI that outputs [json] on stdout (written to a file to avoid shell-escaping issues). */
    private fun jsonCli(json: String): DopplerCliClient {
        val jsonFile = File.createTempFile("fixture-", ".json", tempDir)
        jsonFile.writeText(json)
        return fakeCli("cat '${jsonFile.absolutePath}'")
    }

    /** CLI that writes [stderr] to stderr and exits with [exitCode]. */
    private fun errorCli(stderr: String, exitCode: Int = 1): DopplerCliClient {
        val stderrFile = File.createTempFile("stderr-", ".txt", tempDir)
        stderrFile.writeText(stderr)
        return fakeCli("cat '${stderrFile.absolutePath}' >&2; exit $exitCode")
    }

    private fun makeService(cli: DopplerCliClient): DopplerProjectService =
        DopplerProjectService(projectFixture.get()) { cli }

    // ── isApplicableFor ───────────────────────────────────────────────────────

    private fun gradleRunConfig(): ExternalSystemRunConfiguration {
        val type = GradleExternalTaskConfigurationType.getInstance()
        val settings = RunManager.getInstance(projectFixture.get()).createConfiguration("test", type.factory)
        return settings.configuration as ExternalSystemRunConfiguration
    }

    @Test
    fun `isApplicableFor returns true for Gradle run configuration`() {
        assertThat(ext.isApplicableFor(gradleRunConfig())).isTrue()
    }

    @Test
    fun `isApplicableFor returns false for non-Gradle external system configuration`() {
        val config = gradleRunConfig()
        config.settings.externalSystemIdString = "MAVEN"
        assertThat(ext.isApplicableFor(config)).isFalse()
    }

    // ── injectSecrets — happy path ─────────────────────────────────────────────

    @Test
    fun `injectSecrets adds Doppler secrets to command line environment`() {
        configureSettings()
        val cmdLine = GeneralCommandLine()
        ext.injectSecrets(
            projectFixture.get(), emptyMap(), "cfg-01", cmdLine,
            makeService(jsonCli("""{"FAKE_SECRET":"fake-value"}""")),
        )
        assertThat(cmdLine.environment).containsEntry("FAKE_SECRET", "fake-value")
    }

    @Test
    fun `injectSecrets does not modify command line when disabled`() {
        configureSettings(enabled = false)
        val cmdLine = GeneralCommandLine()
        val envBefore = cmdLine.environment.toMap()
        ext.injectSecrets(
            projectFixture.get(), emptyMap(), "cfg-02", cmdLine,
            makeService(jsonCli("""{"UNUSED":"value"}""")),
        )
        assertThat(cmdLine.environment).isEqualTo(envBefore)
    }

    @Test
    fun `injectSecrets respects local-wins policy, run-config env var survives Doppler collision`() {
        configureSettings()
        val cmdLine = GeneralCommandLine()
        ext.injectSecrets(
            projectFixture.get(),
            mapOf("FAKE_DB_URL" to "local-val"),
            "cfg-03",
            cmdLine,
            makeService(jsonCli("""{"FAKE_DB_URL":"doppler-val","FAKE_EXTRA":"xyz"}""")),
        )
        assertThat(cmdLine.environment)
            .containsEntry("FAKE_DB_URL", "local-val")   // local wins
            .containsEntry("FAKE_EXTRA", "xyz")           // Doppler-only var injected
    }

    // ── injectSecrets — shadow notification ────────────────────────────────────

    @Test
    fun `injectSecrets fires shadow warning exactly once per config name`() {
        configureSettings()
        val warnings = mutableListOf<String>()
        val svc = makeService(jsonCli("""{"FAKE_KEY":"doppler-val"}"""))
        val configName = "shadow-once-test"

        repeat(2) {
            ext.injectSecrets(
                projectFixture.get(), mapOf("FAKE_KEY" to "local-val"), configName,
                GeneralCommandLine(), svc,
                notifyWarning = { _, msg -> warnings += msg },
            )
        }

        assertThat(warnings).hasSize(1)
    }

    @Test
    fun `injectSecrets shadow warning message lists shadowed keys but never their values`() {
        configureSettings()
        val warnings = mutableListOf<String>()

        ext.injectSecrets(
            projectFixture.get(),
            mapOf("FAKE_KEY" to "also-secret"),
            "shadow-msg-test",
            GeneralCommandLine(),
            makeService(jsonCli("""{"FAKE_KEY":"do-not-leak-this"}""")),
            notifyWarning = { _, msg -> warnings += msg },
        )

        assertThat(warnings).hasSize(1)
        val msg = warnings.first()
        assertThat(msg).contains("FAKE_KEY")                // key name present
        assertThat(msg).doesNotContain("do-not-leak-this")  // Doppler value absent
        assertThat(msg).doesNotContain("also-secret")       // local value absent
        assertThat(msg).contains("shadow-msg-test")         // config name for context
    }

    // ── injectSecrets — failure path ──────────────────────────────────────────

    @Test
    fun `injectSecrets notifies error and rethrows on DopplerFetchException`() {
        configureSettings()
        val errors = mutableListOf<String>()

        assertThatThrownBy {
            ext.injectSecrets(
                projectFixture.get(), emptyMap(), "err-test", GeneralCommandLine(),
                makeService(errorCli("config not found")),
                notifyError = { _, msg -> errors += msg },
            )
        }.isInstanceOf(DopplerFetchException::class.java)
            .hasMessage("config not found")

        assertThat(errors).containsExactly("config not found")
    }

    @Test
    fun `injectSecrets error notification message is CLI stderr, never a secret value`() {
        configureSettings()
        val errors = mutableListOf<String>()

        try {
            ext.injectSecrets(
                projectFixture.get(), emptyMap(), "err-safe-test", GeneralCommandLine(),
                makeService(errorCli("config 'dev' not found for project 'test-proj'")),
                notifyError = { _, msg -> errors += msg },
            )
        } catch (_: DopplerFetchException) { /* expected */ }

        assertThat(errors).hasSize(1)
        assertThat(errors.first()).doesNotContain("supersecret-value")
    }
}

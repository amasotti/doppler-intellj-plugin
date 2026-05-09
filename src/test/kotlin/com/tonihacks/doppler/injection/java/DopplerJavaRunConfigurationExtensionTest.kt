package com.tonihacks.doppler.injection.java

import com.intellij.execution.configurations.JavaParameters
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.tonihacks.doppler.cli.DopplerCliClient
import com.tonihacks.doppler.service.DopplerFetchException
import com.tonihacks.doppler.service.DopplerProjectService
import com.tonihacks.doppler.settings.DopplerSettingsState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@TestApplication
class DopplerJavaRunConfigurationExtensionTest {

    private val projectFixture = projectFixture()
    private val ext = DopplerJavaRunConfigurationExtension()

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun resetSettings() {
        DopplerSettingsState.getInstance(projectFixture.get())
            .loadState(DopplerSettingsState.State())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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

    // ── injectSecrets — happy path ─────────────────────────────────────────────

    @Test
    fun `injectSecrets adds Doppler secrets to JavaParameters env`() {
        configureSettings()
        val params = JavaParameters()

        ext.injectSecrets(
            project = projectFixture.get(),
            existingEnv = emptyMap(),
            configName = "java-cfg-01",
            params = params,
            service = makeService(jsonCli("""{"FAKE_SECRET":"fake-value"}""")),
        )

        assertThat(params.env).containsEntry("FAKE_SECRET", "fake-value")
    }

    @Test
    fun `injectSecrets does not modify params when disabled`() {
        configureSettings(enabled = false)
        val params = JavaParameters()
        params.env["EXISTING_VAR"] = "original"
        val envBefore = params.env.toMap()

        ext.injectSecrets(
            project = projectFixture.get(),
            existingEnv = emptyMap(),
            configName = "java-cfg-02",
            params = params,
            service = makeService(jsonCli("""{"UNUSED":"value"}""")),
        )

        assertThat(params.env).isEqualTo(envBefore)
    }

    @Test
    fun `injectSecrets respects local-wins policy, run-config env var survives Doppler collision`() {
        configureSettings()
        val params = JavaParameters()

        ext.injectSecrets(
            project = projectFixture.get(),
            existingEnv = mapOf("FAKE_DB_URL" to "local-val"),
            configName = "java-cfg-03",
            params = params,
            service = makeService(jsonCli("""{"FAKE_DB_URL":"doppler-val","FAKE_EXTRA":"xyz"}""")),
        )

        assertThat(params.env)
            .containsEntry("FAKE_DB_URL", "local-val")   // local wins
            .containsEntry("FAKE_EXTRA", "xyz")           // Doppler-only var injected
    }

    @Test
    fun `injectSecrets preserves pre-existing params env vars not in Doppler or existingEnv`() {
        configureSettings()
        val params = JavaParameters()
        params.env["RUNNER_VAR"] = "runner-set"  // set by another extension / runner

        ext.injectSecrets(
            project = projectFixture.get(),
            existingEnv = mapOf("RUNNER_VAR" to "runner-set"),
            configName = "java-cfg-04",
            params = params,
            service = makeService(jsonCli("""{"DOPPLER_ONLY":"injected"}""")),
        )

        // RUNNER_VAR is in existingEnv so it is preserved by SecretMerger (local wins)
        assertThat(params.env)
            .containsEntry("RUNNER_VAR", "runner-set")
            .containsEntry("DOPPLER_ONLY", "injected")
    }

    // ── injectSecrets — shadow notification ────────────────────────────────────

    @Test
    fun `injectSecrets fires shadow warning exactly once per config name`() {
        configureSettings()
        val warnings = mutableListOf<String>()
        val svc = makeService(jsonCli("""{"FAKE_KEY":"doppler-val"}"""))
        val configName = "java-shadow-once"

        repeat(2) {
            ext.injectSecrets(
                project = projectFixture.get(),
                existingEnv = mapOf("FAKE_KEY" to "local-val"),
                configName = configName,
                params = JavaParameters(),
                service = svc,
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
            project = projectFixture.get(),
            existingEnv = mapOf("FAKE_KEY" to "also-secret"),
            configName = "java-shadow-msg",
            params = JavaParameters(),
            service = makeService(jsonCli("""{"FAKE_KEY":"do-not-leak-this"}""")),
            notifyWarning = { _, msg -> warnings += msg },
        )

        assertThat(warnings).hasSize(1)
        val msg = warnings.first()
        assertThat(msg).contains("FAKE_KEY")               // key name present
        assertThat(msg).doesNotContain("do-not-leak-this") // Doppler value absent
        assertThat(msg).doesNotContain("also-secret")      // local value absent
        assertThat(msg).contains("java-shadow-msg")        // config name for context
    }

    // ── injectSecrets — failure path ──────────────────────────────────────────

    @Test
    fun `injectSecrets notifies error and rethrows on DopplerFetchException`() {
        configureSettings()
        val errors = mutableListOf<String>()

        assertThatThrownBy {
            ext.injectSecrets(
                project = projectFixture.get(),
                existingEnv = emptyMap(),
                configName = "java-err-test",
                params = JavaParameters(),
                service = makeService(errorCli("config not found")),
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
                project = projectFixture.get(),
                existingEnv = emptyMap(),
                configName = "java-err-safe",
                params = JavaParameters(),
                service = makeService(errorCli("config 'dev' not found for project 'test-proj'")),
                notifyError = { _, msg -> errors += msg },
            )
        } catch (_: DopplerFetchException) { /* expected */ }

        assertThat(errors).hasSize(1)
        assertThat(errors.first()).doesNotContain("supersecret-value")
    }
}

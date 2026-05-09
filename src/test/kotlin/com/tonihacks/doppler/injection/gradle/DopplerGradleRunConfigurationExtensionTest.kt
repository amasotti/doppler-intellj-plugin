package com.tonihacks.doppler.injection.gradle

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.tonihacks.doppler.cli.DopplerCliClient
import com.tonihacks.doppler.cli.DopplerResult
import com.tonihacks.doppler.notification.DopplerNotifier
import com.tonihacks.doppler.service.DopplerFetchException
import com.tonihacks.doppler.service.DopplerProjectService
import com.tonihacks.doppler.settings.DopplerSettingsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@TestApplication
class DopplerGradleRunConfigurationExtensionTest {

    private val projectFixture = projectFixture()
    private val ext = DopplerGradleRunConfigurationExtension()

    @BeforeEach
    fun resetSettings() {
        DopplerSettingsState.getInstance(projectFixture.get())
            .loadState(DopplerSettingsState.State())
    }

    @AfterEach
    fun cleanupMocks() {
        unmockkObject(DopplerNotifier)
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

    /** Creates a [DopplerProjectService] backed by [mockCli] without touching the container. */
    private fun makeService(mockCli: DopplerCliClient): DopplerProjectService =
        DopplerProjectService(projectFixture.get()) { mockCli }

    private fun mockGradleConfig(
        existingEnv: Map<String, String> = emptyMap(),
        systemId: ProjectSystemId = GradleConstants.SYSTEM_ID,
    ): ExternalSystemRunConfiguration {
        val settings = mockk<ExternalSystemTaskExecutionSettings>()
        every { settings.externalSystemId } returns systemId
        val config = mockk<ExternalSystemRunConfiguration>()
        every { config.settings } returns settings
        return config
    }

    // ── isApplicableFor ───────────────────────────────────────────────────────

    @Test
    fun `isApplicableFor returns true for Gradle run configuration`() {
        assertThat(ext.isApplicableFor(mockGradleConfig(systemId = GradleConstants.SYSTEM_ID))).isTrue()
    }

    @Test
    fun `isApplicableFor returns false for non-Gradle external system configuration`() {
        assertThat(ext.isApplicableFor(mockGradleConfig(systemId = ProjectSystemId("MAVEN")))).isFalse()
    }

    // ── injectSecrets — happy path ─────────────────────────────────────────────

    @Test
    fun `injectSecrets adds Doppler secrets to command line environment`() {
        val mockCli = mockk<DopplerCliClient>()
        every { mockCli.downloadSecrets("test-proj", "dev") } returns
            DopplerResult.Success(mapOf("FAKE_SECRET" to "fake-value"))
        configureSettings()

        val cmdLine = GeneralCommandLine()
        ext.injectSecrets(projectFixture.get(), emptyMap(), "inject-001", cmdLine, makeService(mockCli))

        assertThat(cmdLine.environment).containsEntry("FAKE_SECRET", "fake-value")
    }

    @Test
    fun `injectSecrets does not modify command line when service returns empty map`() {
        configureSettings(enabled = false)
        val mockCli = mockk<DopplerCliClient>(relaxed = true)

        val cmdLine = GeneralCommandLine()
        val envBefore = cmdLine.environment.toMap()
        ext.injectSecrets(projectFixture.get(), emptyMap(), "inject-002", cmdLine, makeService(mockCli))

        assertThat(cmdLine.environment).isEqualTo(envBefore)
    }

    @Test
    fun `injectSecrets respects local-wins policy — run-config env var survives Doppler collision`() {
        val mockCli = mockk<DopplerCliClient>()
        every { mockCli.downloadSecrets("test-proj", "dev") } returns
            DopplerResult.Success(mapOf("FAKE_DB_URL" to "doppler-val", "FAKE_EXTRA" to "xyz"))
        configureSettings()

        val cmdLine = GeneralCommandLine()
        ext.injectSecrets(
            projectFixture.get(),
            mapOf("FAKE_DB_URL" to "local-val"),
            "inject-003",
            cmdLine,
            makeService(mockCli),
        )

        assertThat(cmdLine.environment)
            .containsEntry("FAKE_DB_URL", "local-val")   // local wins
            .containsEntry("FAKE_EXTRA", "xyz")            // Doppler-only var injected
    }

    // ── injectSecrets — shadow notification ────────────────────────────────────

    @Test
    fun `injectSecrets fires shadow warning exactly once per config name`() {
        mockkObject(DopplerNotifier)
        every { DopplerNotifier.notifyWarning(any(), any()) } returns Unit

        val mockCli = mockk<DopplerCliClient>()
        every { mockCli.downloadSecrets("test-proj", "dev") } returns
            DopplerResult.Success(mapOf("FAKE_KEY" to "doppler-val"))
        configureSettings()
        val svc = makeService(mockCli)
        val configName = "shadow-once-test"

        ext.injectSecrets(projectFixture.get(), mapOf("FAKE_KEY" to "local-val"), configName, GeneralCommandLine(), svc)
        ext.injectSecrets(projectFixture.get(), mapOf("FAKE_KEY" to "local-val"), configName, GeneralCommandLine(), svc)

        verify(exactly = 1) { DopplerNotifier.notifyWarning(any(), any()) }
    }

    @Test
    fun `injectSecrets shadow warning message lists shadowed keys but never their values`() {
        mockkObject(DopplerNotifier)
        val capturedMessages = mutableListOf<String>()
        every { DopplerNotifier.notifyWarning(any(), capture(capturedMessages)) } returns Unit

        val mockCli = mockk<DopplerCliClient>()
        every { mockCli.downloadSecrets("test-proj", "dev") } returns
            DopplerResult.Success(mapOf("FAKE_KEY" to "do-not-leak-this"))
        configureSettings()

        ext.injectSecrets(
            projectFixture.get(),
            mapOf("FAKE_KEY" to "also-secret"),
            "shadow-msg-test",
            GeneralCommandLine(),
            makeService(mockCli),
        )

        assertThat(capturedMessages).hasSize(1)
        val msg = capturedMessages.first()
        assertThat(msg).contains("FAKE_KEY")               // key name present
        assertThat(msg).doesNotContain("do-not-leak-this") // Doppler value absent
        assertThat(msg).doesNotContain("also-secret")      // local value absent
        assertThat(msg).contains("shadow-msg-test")        // config name for context
    }

    // ── injectSecrets — failure path ──────────────────────────────────────────

    @Test
    fun `injectSecrets notifies error and rethrows on DopplerFetchException`() {
        mockkObject(DopplerNotifier)
        every { DopplerNotifier.notifyError(any(), any()) } returns Unit

        val mockCli = mockk<DopplerCliClient>()
        every { mockCli.downloadSecrets("test-proj", "dev") } returns
            DopplerResult.Failure("config not found", 1)
        configureSettings()

        assertThatThrownBy {
            ext.injectSecrets(projectFixture.get(), emptyMap(), "err-test", GeneralCommandLine(), makeService(mockCli))
        }.isInstanceOf(DopplerFetchException::class.java)
            .hasMessage("config not found")

        verify(exactly = 1) { DopplerNotifier.notifyError(any(), "config not found") }
    }

    @Test
    fun `injectSecrets error notification message is CLI stderr, never a secret value`() {
        mockkObject(DopplerNotifier)
        val capturedErrors = mutableListOf<String>()
        every { DopplerNotifier.notifyError(any(), capture(capturedErrors)) } returns Unit

        val mockCli = mockk<DopplerCliClient>()
        every { mockCli.downloadSecrets(any(), any()) } returns
            DopplerResult.Failure("config 'dev' not found for project 'test-proj'", 1)
        configureSettings()

        try {
            ext.injectSecrets(projectFixture.get(), emptyMap(), "err-safe-test", GeneralCommandLine(), makeService(mockCli))
        } catch (_: DopplerFetchException) { /* expected */ }

        assertThat(capturedErrors).hasSize(1)
        assertThat(capturedErrors.first()).doesNotContain("supersecret-value")
    }
}

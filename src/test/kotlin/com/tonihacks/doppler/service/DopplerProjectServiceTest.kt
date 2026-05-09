package com.tonihacks.doppler.service

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.tonihacks.doppler.cli.DopplerCliClient
import com.tonihacks.doppler.cli.DopplerResult
import com.tonihacks.doppler.settings.DopplerSettingsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

@TestApplication
class DopplerProjectServiceTest {

    private val projectFixture = projectFixture()

    private fun configure(
        enabled: Boolean = true,
        dopplerProject: String = "my-service",
        dopplerConfig: String = "dev",
        cacheTtlSeconds: Int = 60,
    ) {
        val s = DopplerSettingsState.getInstance(projectFixture.get()).state
        s.enabled = enabled
        s.dopplerProject = dopplerProject
        s.dopplerConfig = dopplerConfig
        s.cacheTtlSeconds = cacheTtlSeconds
    }

    @Test
    fun `fetchSecrets returns empty map when disabled and never calls CLI`() {
        val mockCli = mockk<DopplerCliClient>(relaxed = true)
        configure(enabled = false)
        val svc = DopplerProjectService(projectFixture.get()) { mockCli }

        assertThat(svc.fetchSecrets()).isEmpty()
        verify(exactly = 0) { mockCli.downloadSecrets(any(), any()) }
    }

    @Test
    fun `fetchSecrets calls CLI on cache miss and reuses cache on second call`() {
        val expected = mapOf("API_KEY" to "abc", "DB_URL" to "postgres://x")
        val mockCli = mockk<DopplerCliClient>()
        every { mockCli.downloadSecrets("my-service", "dev") } returns DopplerResult.Success(expected)
        configure()
        val svc = DopplerProjectService(projectFixture.get()) { mockCli }

        val first = svc.fetchSecrets()
        val second = svc.fetchSecrets()

        assertThat(first).isEqualTo(expected)
        assertThat(second).isEqualTo(expected)
        verify(exactly = 1) { mockCli.downloadSecrets("my-service", "dev") }
    }

    @Test
    fun `invalidateCache forces a re-fetch from CLI`() {
        val mockCli = mockk<DopplerCliClient>()
        every { mockCli.downloadSecrets(any(), any()) } returns DopplerResult.Success(mapOf("K" to "v"))
        configure()
        val svc = DopplerProjectService(projectFixture.get()) { mockCli }

        svc.fetchSecrets()
        svc.invalidateCache()
        svc.fetchSecrets()

        verify(exactly = 2) { mockCli.downloadSecrets("my-service", "dev") }
    }

    @Test
    fun `fetchSecrets throws DopplerFetchException with verbatim CLI error on Failure`() {
        val mockCli = mockk<DopplerCliClient>()
        every { mockCli.downloadSecrets(any(), any()) } returns DopplerResult.Failure("config not found", 1)
        configure()
        val svc = DopplerProjectService(projectFixture.get()) { mockCli }

        assertThatThrownBy { svc.fetchSecrets() }
            .isInstanceOf(DopplerFetchException::class.java)
            .hasMessage("config not found")
    }

    @Test
    fun `fetchSecrets does not cache failures`() {
        val mockCli = mockk<DopplerCliClient>()
        every { mockCli.downloadSecrets(any(), any()) } returns
            DopplerResult.Failure("transient network error", -1) andThen
            DopplerResult.Success(mapOf("K" to "v"))
        configure()
        val svc = DopplerProjectService(projectFixture.get()) { mockCli }

        runCatching { svc.fetchSecrets() }
        val secondAttempt = svc.fetchSecrets()

        assertThat(secondAttempt).containsEntry("K", "v")
        verify(exactly = 2) { mockCli.downloadSecrets(any(), any()) }
    }

    @Test
    fun `isCliAvailable reflects CLI version probe result`() {
        val happyCli = mockk<DopplerCliClient>()
        every { happyCli.version() } returns DopplerResult.Success("v3.69.0")
        val sadCli = mockk<DopplerCliClient>()
        every { sadCli.version() } returns DopplerResult.Failure("not found")

        val happy = DopplerProjectService(projectFixture.get()) { happyCli }
        val sad = DopplerProjectService(projectFixture.get()) { sadCli }

        assertThat(happy.isCliAvailable()).isTrue()
        assertThat(sad.isCliAvailable()).isFalse()
    }

    @Test
    fun `isAuthenticated reflects CLI me probe result`() {
        val authedCli = mockk<DopplerCliClient>()
        every { authedCli.me() } returns DopplerResult.Success(
            com.tonihacks.doppler.cli.DopplerUser(email = "", name = "my-laptop")
        )
        val unauthedCli = mockk<DopplerCliClient>()
        every { unauthedCli.me() } returns DopplerResult.Failure("unauthorized")

        val authed = DopplerProjectService(projectFixture.get()) { authedCli }
        val unauthed = DopplerProjectService(projectFixture.get()) { unauthedCli }

        assertThat(authed.isAuthenticated()).isTrue()
        assertThat(unauthed.isAuthenticated()).isFalse()
    }
}

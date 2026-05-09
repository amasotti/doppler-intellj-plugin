package com.tonihacks.doppler.service

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.tonihacks.doppler.cli.DopplerCliClient
import com.tonihacks.doppler.settings.DopplerSettingsState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@TestApplication
class DopplerProjectServiceTest {

    private val projectFixture = projectFixture()

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun resetSettings() {
        // Tests share the project fixture; reset to defaults so a previous test's mutations
        // don't bleed across (e.g. dopplerProject left over from an earlier `configure(...)`).
        DopplerSettingsState.getInstance(projectFixture.get()).loadState(DopplerSettingsState.State())
    }

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

    private fun fakeCli(script: String): DopplerCliClient {
        val f = File.createTempFile("doppler-", ".sh", tempDir)
        f.writeText("#!/bin/sh\n$script\n")
        f.setExecutable(true)
        return DopplerCliClient(f.absolutePath)
    }

    private fun marker(name: String = "calls.txt"): File = File(tempDir, name)

    private fun callCount(marker: File): Int =
        marker.takeIf(File::exists)?.readLines()?.size ?: 0

    private fun successfulSecretsCli(
        secretsJson: String,
        marker: File = marker(),
    ): DopplerCliClient =
        fakeCli(
            """
            echo "${'$'}*" >> "${marker.absolutePath}"
            cat <<'EOF'
            $secretsJson
            EOF
            """.trimIndent()
        )

    private fun failingSecretsCli(
        stderr: String,
        marker: File = marker(),
        exitCode: Int = 1,
    ): DopplerCliClient =
        fakeCli(
            """
            echo "${'$'}*" >> "${marker.absolutePath}"
            echo "$stderr" >&2
            exit $exitCode
            """.trimIndent()
        )

    @Test
    fun `fetchSecrets returns empty map when disabled and never calls CLI`() {
        val marker = marker()
        val cli = successfulSecretsCli("""{"UNUSED":"value"}""", marker)
        configure(enabled = false)
        val svc = DopplerProjectService(projectFixture.get()) { cli }

        assertThat(svc.fetchSecrets()).isEmpty()
        assertThat(callCount(marker)).isZero()
    }

    @Test
    fun `fetchSecrets returns empty map when project slug is blank, even if enabled`() {
        val marker = marker()
        val cli = successfulSecretsCli("""{"UNUSED":"value"}""", marker)
        configure(dopplerProject = "", dopplerConfig = "dev")
        val svc = DopplerProjectService(projectFixture.get()) { cli }

        assertThat(svc.fetchSecrets()).isEmpty()
        assertThat(callCount(marker)).isZero()
    }

    @Test
    fun `fetchSecrets returns empty map when config slug is blank, even if enabled`() {
        val marker = marker()
        val cli = successfulSecretsCli("""{"UNUSED":"value"}""", marker)
        configure(dopplerProject = "my-service", dopplerConfig = "")
        val svc = DopplerProjectService(projectFixture.get()) { cli }

        assertThat(svc.fetchSecrets()).isEmpty()
        assertThat(callCount(marker)).isZero()
    }

    @Test
    fun `fetchSecrets calls CLI on cache miss and reuses cache on second call`() {
        val expected = mapOf("API_KEY" to "abc", "DB_URL" to "postgres://x")
        val marker = marker()
        val cli = successfulSecretsCli("""{"API_KEY":"abc","DB_URL":"postgres://x"}""", marker)
        configure()
        val svc = DopplerProjectService(projectFixture.get()) { cli }

        val first = svc.fetchSecrets()
        val second = svc.fetchSecrets()

        assertThat(first).containsExactlyInAnyOrderEntriesOf(expected)
        assertThat(second).containsExactlyInAnyOrderEntriesOf(expected)
        assertThat(callCount(marker)).isEqualTo(1)
        assertThat(marker.readText()).contains(
            "secrets download --project my-service --config dev --no-file --format json"
        )
    }

    @Test
    fun `returned map redacts toString to defend against stray log statements`() {
        val cli = successfulSecretsCli("""{"API_KEY":"supersecret-value","DB_URL":"postgres://x"}""")
        configure()
        val svc = DopplerProjectService(projectFixture.get()) { cli }

        val rendered = svc.fetchSecrets().toString()

        assertThat(rendered).doesNotContain("supersecret-value", "postgres", "API_KEY", "DB_URL")
        assertThat(rendered).isEqualTo("[REDACTED x2]")
    }

    @Test
    fun `redactedView entries and values still leak — documented limitation pinned to keep reviewers honest`() {
        // The wrapper only overrides toString() on the Map itself. `entries`/`values`/`keys`
        // proxy to the inner map, whose toString() renders KEY=VALUE. This test exists to
        // PIN that limitation: if a future maintainer "tightens" the wrapper to also redact
        // these collections, env-injection callers iterating the map will see redacted
        // sentinels and break. The only correct fix would be a different return type
        // (e.g. SecretBundle) — that's a Phase 7+ decision, not a sneak fix in cli/service.
        val cli = successfulSecretsCli("""{"API_KEY":"supersecret-value"}""")
        configure()
        val svc = DopplerProjectService(projectFixture.get()) { cli }

        val m = svc.fetchSecrets()

        assertThat(m.entries.toString()).contains("supersecret-value")
        assertThat(m.values.toString()).contains("supersecret-value")
    }

    @Test
    fun `cacheTtlSeconds setting is read on each fetch — TTL=0 forces re-fetch`() {
        val marker = marker()
        val cli = successfulSecretsCli("""{"K":"v"}""", marker)
        configure(cacheTtlSeconds = 0)
        val svc = DopplerProjectService(projectFixture.get()) { cli }

        svc.fetchSecrets()
        svc.fetchSecrets()

        // TTL=0 → entry expires immediately on read (`now >= expiresAt`), so each fetch is a CLI call.
        assertThat(callCount(marker)).isEqualTo(2)
    }

    @Test
    fun `invalidateCache forces a re-fetch from CLI`() {
        val marker = marker()
        val cli = successfulSecretsCli("""{"K":"v"}""", marker)
        configure()
        val svc = DopplerProjectService(projectFixture.get()) { cli }

        svc.fetchSecrets()
        svc.invalidateCache()
        svc.fetchSecrets()

        assertThat(callCount(marker)).isEqualTo(2)
    }

    @Test
    fun `fetchSecrets throws DopplerFetchException with verbatim CLI error on Failure`() {
        val cli = failingSecretsCli("config not found", exitCode = 1)
        configure()
        val svc = DopplerProjectService(projectFixture.get()) { cli }

        val caught: DopplerFetchException? = try {
            svc.fetchSecrets()
            null
        } catch (e: DopplerFetchException) {
            e
        }

        assertThat(caught).isNotNull
        assertThat(caught?.message).isEqualTo("config not found")
        // Pin "no chained cause" — a future maintainer wrapping a prior throwable could
        // smuggle CLI internals (or worse) into the exception's stacktrace via initCause.
        assertThat(caught?.cause).isNull()
        assertThat(caught?.suppressed).isEmpty()
    }

    @Test
    fun `fetchSecrets does not cache failures`() {
        val marker = marker()
        val stateFile = File(tempDir, "secrets-call-state.txt")
        val cli = fakeCli(
            """
            echo "${'$'}*" >> "${marker.absolutePath}"
            if [ ! -f "${stateFile.absolutePath}" ]; then
              touch "${stateFile.absolutePath}"
              echo "transient network error" >&2
              exit 1
            fi
            cat <<'EOF'
            {"K":"v"}
            EOF
            """.trimIndent()
        )
        configure()
        val svc = DopplerProjectService(projectFixture.get()) { cli }

        try {
            svc.fetchSecrets()
        } catch (_: DopplerFetchException) {
            // expected on first call
        }
        val secondAttempt = svc.fetchSecrets()

        assertThat(secondAttempt).containsEntry("K", "v")
        assertThat(callCount(marker)).isEqualTo(2)
    }

    @Test
    fun `isCliAvailable reflects CLI version probe result`() {
        val happyCli = fakeCli("""echo "v3.69.0"""")
        val sadCli = fakeCli("""echo "not found" >&2; exit 1""")

        val happy = DopplerProjectService(projectFixture.get()) { happyCli }
        val sad = DopplerProjectService(projectFixture.get()) { sadCli }

        assertThat(happy.isCliAvailable()).isTrue()
        assertThat(sad.isCliAvailable()).isFalse()
    }

    @Test
    fun `isAuthenticated reflects CLI me probe result`() {
        val authedCli = fakeCli(
            """
            cat <<'EOF'
            {"name":"my-laptop"}
            EOF
            """.trimIndent()
        )
        val unauthedCli = fakeCli("""echo "unauthorized" >&2; exit 1""")

        val authed = DopplerProjectService(projectFixture.get()) { authedCli }
        val unauthed = DopplerProjectService(projectFixture.get()) { unauthedCli }

        assertThat(authed.isAuthenticated()).isTrue()
        assertThat(unauthed.isAuthenticated()).isFalse()
    }
}

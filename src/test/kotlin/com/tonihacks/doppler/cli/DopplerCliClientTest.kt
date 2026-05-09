package com.tonihacks.doppler.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DopplerCliClientTest {

    @TempDir
    lateinit var tempDir: File

    private fun fakeCli(script: String): String {
        val f = File(tempDir, "doppler")
        f.writeText("#!/bin/sh\n$script\n")
        f.setExecutable(true)
        return f.absolutePath
    }

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/cli/$name")!!.readText()

    private fun emitFixture(json: String): String = """cat <<'EOF'
$json
EOF"""

    @Test
    fun `version returns trimmed version string`() {
        val cli = DopplerCliClient(fakeCli("""echo "v3.69.0""""))
        val r = cli.version()
        check(r is DopplerResult.Success<String>) { "expected Success, got $r" }
        assertThat(r.value).isEqualTo("v3.69.0")
    }

    @Test
    fun `me parses CLI-token shape with name and empty email`() {
        // Real-world `doppler me --json` for a CLI token has no email field.
        // User-token shape has email; the parser falls back to "" when absent.
        val cli = DopplerCliClient(fakeCli(emitFixture(fixture("me.json"))))
        val r = cli.me()
        check(r is DopplerResult.Success<DopplerUser>) { "expected Success, got $r" }
        assertThat(r.value.name).isEqualTo("my-laptop")
        assertThat(r.value.email).isEmpty()
    }

    @Test
    fun `listProjects parses array fixture`() {
        val cli = DopplerCliClient(fakeCli(emitFixture(fixture("projects.json"))))
        val r = cli.listProjects()
        check(r is DopplerResult.Success<List<DopplerProject>>) { "expected Success, got $r" }
        assertThat(r.value).hasSize(2)
        assertThat(r.value.map { it.slug }).containsExactly("my-service", "another-service")
    }

    @Test
    fun `listConfigs parses array fixture`() {
        val cli = DopplerCliClient(fakeCli(emitFixture(fixture("configs.json"))))
        val r = cli.listConfigs("my-service")
        check(r is DopplerResult.Success<List<DopplerConfig>>) { "expected Success, got $r" }
        assertThat(r.value.map { it.name }).containsExactly("dev", "stg", "prd")
        assertThat(r.value).allSatisfy { assertThat(it.project).isEqualTo("my-service") }
    }

    @Test
    fun `downloadSecrets parses flat key-value JSON`() {
        val cli = DopplerCliClient(fakeCli(emitFixture(fixture("secrets_download.json"))))
        val r = cli.downloadSecrets("my-service", "dev")
        check(r is DopplerResult.Success<Map<String, String>>) { "expected Success, got $r" }
        assertThat(r.value)
            .containsEntry("DATABASE_URL", "postgres://localhost/dev")
            .containsEntry("API_KEY", "test-api-key-123")
            .containsEntry("DEBUG", "true")
            .containsEntry("PORT", "3000")
            .hasSize(4)
    }

    @Test
    fun `setSecret reads value from stdin not argv`() {
        val marker = File(tempDir, "marker.txt")
        // Script records: which positional args were passed AND what came on stdin.
        // The secret value must show up in stdin only, never in argv.
        val script = """
            read -r piped_value || true
            {
                echo "argv=${'$'}@"
                echo "stdin=${'$'}piped_value"
            } > "${marker.absolutePath}"
        """.trimIndent()
        val cli = DopplerCliClient(fakeCli(script))

        val r = cli.setSecret("my-service", "dev", "API_KEY", "super-secret-value")
        check(r is DopplerResult.Success<Unit>) { "expected Success, got $r" }

        val recorded = marker.readText()
        val argvLine = recorded.lineSequence().first { it.startsWith("argv=") }
        val stdinLine = recorded.lineSequence().first { it.startsWith("stdin=") }
        assertThat(argvLine).contains("secrets", "set", "API_KEY", "--project", "my-service")
        assertThat(argvLine).doesNotContain("super-secret-value")
        assertThat(stdinLine).isEqualTo("stdin=super-secret-value")
    }

    @Test
    fun `deleteSecret invokes correct CLI args`() {
        val marker = File(tempDir, "marker.txt")
        val script = """echo "${'$'}@" > "${marker.absolutePath}""""
        val cli = DopplerCliClient(fakeCli(script))

        val r = cli.deleteSecret("my-service", "dev", "OLD_KEY")
        check(r is DopplerResult.Success<Unit>) { "expected Success, got $r" }

        val argv = marker.readText()
        assertThat(argv).contains("secrets", "delete", "OLD_KEY", "--silent", "--yes")
    }

    @Test
    fun `nonzero exit returns Failure with stderr verbatim`() {
        val cli = DopplerCliClient(fakeCli("""echo "config not found" >&2; exit 1"""))
        val r = cli.downloadSecrets("missing", "dev")
        check(r is DopplerResult.Failure) { "expected Failure, got $r" }
        assertThat(r.error).isEqualTo("config not found")
        assertThat(r.exitCode).isEqualTo(1)
    }

    @Test
    fun `missing binary returns Failure not exception`() {
        val cli = DopplerCliClient("/nonexistent/doppler-binary")
        val r = cli.version()
        check(r is DopplerResult.Failure) { "expected Failure, got $r" }
        assertThat(r.error).contains("not found")
    }

    @Test
    fun `timeout returns Failure`() {
        val cli = DopplerCliClient(
            cliPath = fakeCli("sleep 30"),
            timeoutMs = 200,
        )
        val r = cli.version()
        check(r is DopplerResult.Failure) { "expected Failure, got $r" }
        assertThat(r.error).contains("timed out")
    }

    @Test
    fun `malformed JSON returns Failure not exception`() {
        val cli = DopplerCliClient(fakeCli("""echo "not json {[}""""))
        val r = cli.listProjects()
        check(r is DopplerResult.Failure) { "expected Failure, got $r" }
        assertThat(r.error).contains("Failed to parse")
    }
}

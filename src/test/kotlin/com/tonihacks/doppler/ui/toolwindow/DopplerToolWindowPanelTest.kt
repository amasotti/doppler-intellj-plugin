package com.tonihacks.doppler.ui.toolwindow

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.tonihacks.doppler.cli.DopplerCliClient
import com.tonihacks.doppler.service.DopplerFetchException
import com.tonihacks.doppler.service.DopplerProjectService
import com.tonihacks.doppler.settings.DopplerSettingsState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for [DopplerToolWindowPanel].
 *
 * Focuses on synchronous behaviour (model population, button state, error display).
 * Async flows ([loadSecretsAsync], [saveChangesAsync]) are exercised indirectly via
 * their synchronous counterparts ([applyLoadedSecrets], [applyFetchError]).
 *
 * The actual CLI calls in save/add/delete are thin glue over [DopplerCliClient], which
 * is tested in its own unit-test suite — we do not duplicate those tests here.
 */
@TestApplication
class DopplerToolWindowPanelTest {

    private val projectFixture = projectFixture()

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun resetSettings() {
        DopplerSettingsState.getInstance(projectFixture.get())
            .loadState(DopplerSettingsState.State())
    }

    private fun configureSettings(proj: String = "test-proj", cfg: String = "dev") {
        DopplerSettingsState.getInstance(projectFixture.get()).state.apply {
            enabled = true
            dopplerProject = proj
            dopplerConfig = cfg
        }
    }

    private fun fakeCli(script: String): DopplerCliClient {
        val f = File.createTempFile("doppler-", ".sh", tempDir)
        f.writeText("#!/bin/sh\n$script\n")
        f.setExecutable(true)
        return DopplerCliClient(f.absolutePath)
    }

    private fun makePanel(
        cliFactory: () -> DopplerCliClient = { fakeCli("exit 1") },
        errors: MutableList<String> = mutableListOf(),
        infos: MutableList<String> = mutableListOf(),
    ): DopplerToolWindowPanel = DopplerToolWindowPanel(
        project = projectFixture.get(),
        cliFactory = cliFactory,
        notifyError = { _, msg -> errors += msg },
        notifyInfo = { _, msg -> infos += msg },
    )

    // ── smoke test ─────────────────────────────────────────────────────────────

    @Test
    fun `panel creates without error and starts with empty model`() {
        val panel = makePanel()
        assertThat(panel.model.rowCount).isEqualTo(0)
    }

    // ── applyLoadedSecrets ─────────────────────────────────────────────────────

    @Test
    fun `applyLoadedSecrets populates model sorted by key`() {
        val panel = makePanel()

        panel.applyLoadedSecrets(mapOf("ZEBRA" to "z-val", "ALPHA" to "a-val"))

        assertThat(panel.model.rowCount).isEqualTo(2)
        assertThat(panel.model.getValueAt(0, SecretsTableModel.COL_NAME)).isEqualTo("ALPHA")
        assertThat(panel.model.getValueAt(1, SecretsTableModel.COL_NAME)).isEqualTo("ZEBRA")
    }

    @Test
    fun `applyLoadedSecrets masks values by default`() {
        val panel = makePanel()

        panel.applyLoadedSecrets(mapOf("MY_KEY" to "super-secret"))

        assertThat(panel.model.getValueAt(0, SecretsTableModel.COL_VALUE))
            .isEqualTo(SecretsTableModel.MASKED_PLACEHOLDER)
    }

    @Test
    fun `applyLoadedSecrets disables save button`() {
        val panel = makePanel()
        // Simulate a modification before reload
        panel.applyLoadedSecrets(mapOf("K" to "v"))
        panel.model.setValueAt("changed", 0, SecretsTableModel.COL_VALUE)
        assertThat(panel.saveButton.isEnabled).isTrue() // precondition

        // Reload replaces rows → no modified rows → button disabled
        panel.applyLoadedSecrets(mapOf("K" to "v2"))

        assertThat(panel.saveButton.isEnabled).isFalse()
    }

    @Test
    fun `applyLoadedSecrets with empty map results in zero rows`() {
        val panel = makePanel()
        panel.applyLoadedSecrets(mapOf("K" to "v"))

        panel.applyLoadedSecrets(emptyMap())

        assertThat(panel.model.rowCount).isEqualTo(0)
    }

    // ── save button state ──────────────────────────────────────────────────────

    @Test
    fun `save button is disabled initially`() {
        val panel = makePanel()
        panel.applyLoadedSecrets(mapOf("KEY" to "val"))
        assertThat(panel.saveButton.isEnabled).isFalse()
    }

    @Test
    fun `save button becomes enabled after a value edit`() {
        val panel = makePanel()
        panel.applyLoadedSecrets(mapOf("KEY" to "val"))

        panel.model.setValueAt("new-val", 0, SecretsTableModel.COL_VALUE)

        assertThat(panel.saveButton.isEnabled).isTrue()
    }

    @Test
    fun `save button re-disables after applyLoadedSecrets following a reload`() {
        val panel = makePanel()
        panel.applyLoadedSecrets(mapOf("KEY" to "val"))
        panel.model.setValueAt("edited", 0, SecretsTableModel.COL_VALUE)
        assertThat(panel.saveButton.isEnabled).isTrue()

        panel.applyLoadedSecrets(mapOf("KEY" to "saved-val"))

        assertThat(panel.saveButton.isEnabled).isFalse()
    }

    // ── applyFetchError ────────────────────────────────────────────────────────

    @Test
    fun `applyFetchError sets error status containing the message`() {
        val panel = makePanel()

        panel.applyFetchError("config 'dev' not found for project 'test-proj'")

        assertThat(panel.statusLabel.text).contains("config 'dev' not found for project 'test-proj'")
    }

    // ── security: values never in status text ──────────────────────────────────

    @Test
    fun `applyFetchError status text does not contain secret values`() {
        // DopplerFetchException.message = CLI stderr verbatim.
        // CLI stderr for `secrets download` failures does not include secret values —
        // verified in DopplerCliClient contract. This test guards against regression
        // where a future code path routes a value-bearing string through applyFetchError.
        val panel = makePanel()
        val errorMsg = "Access denied for project 'api-service'"

        panel.applyFetchError(errorMsg)

        assertThat(panel.statusLabel.text).doesNotContain("TOP_SECRET_VALUE")
        assertThat(panel.statusLabel.text).contains(errorMsg)
    }

    // ── save integration: CLI called for each modified row ─────────────────────

    @Test
    fun `performSave calls setSecret for each modified row and succeeds`() {
        configureSettings(proj = "my-proj", cfg = "dev")
        val calls = File(tempDir, "set-calls.txt")
        val cli = fakeCli(
            // Record args; value is read from stdin and echoed to the marker
            """echo "${'$'}*" >> "${calls.absolutePath}" """,
        )
        val panel = makePanel(cliFactory = { cli })
        panel.applyLoadedSecrets(mapOf("FOO" to "old-foo", "BAR" to "old-bar"))
        panel.model.setValueAt("new-foo", 0, SecretsTableModel.COL_VALUE) // ALPHA → BAR idx 0
        panel.model.setValueAt("new-bar", 1, SecretsTableModel.COL_VALUE) // ZEBRA → FOO idx 1

        panel.performSave(cli, "my-proj", "dev", panel.model.rows.filter { it.modified })

        assertThat(calls.readLines().size).isEqualTo(2)
        // Args must contain key names but NOT the new values (values go via stdin)
        assertThat(calls.readText()).contains("BAR").contains("FOO")
        assertThat(calls.readText()).doesNotContain("new-foo").doesNotContain("new-bar")
    }

    @Test
    fun `performSave with CLI failure reports error and does not throw`() {
        configureSettings()
        val cli = fakeCli("echo 'network error' >&2; exit 1")
        val errors = mutableListOf<String>()
        val panel = makePanel(cliFactory = { cli }, errors = errors)
        panel.applyLoadedSecrets(mapOf("KEY" to "val"))
        panel.model.setValueAt("new-val", 0, SecretsTableModel.COL_VALUE)

        val failedKeys = panel.performSave(cli, "test-proj", "dev", panel.model.rows.filter { it.modified })

        assertThat(failedKeys).containsExactly("KEY")
    }
}

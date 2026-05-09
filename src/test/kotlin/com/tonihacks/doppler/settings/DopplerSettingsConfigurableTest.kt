package com.tonihacks.doppler.settings

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [DopplerSettingsConfigurable] — focuses on [Configurable] lifecycle
 * logic ([isModified], [apply], [reset], [disposeUIResources]) without driving
 * the actual Swing dialog.
 *
 * **Headless mode note:** `@TestApplication` enables IntelliJ's headless
 * application. Swing components can be constructed and their properties read /
 * written, but no window is shown. The async project-list fetch started by
 * [DopplerSettingsConfigurable.createComponent] will fail silently (no real CLI
 * in test env) and leave the combo boxes empty — that is the expected behaviour.
 */
@TestApplication
class DopplerSettingsConfigurableTest {

    private val projectFixture = projectFixture()

    private lateinit var configurable: DopplerSettingsConfigurable
    private lateinit var settingsState: DopplerSettingsState

    @BeforeEach
    fun setup() {
        settingsState = DopplerSettingsState.getInstance(projectFixture.get())
        // Start from a clean default state before every test to prevent
        // cross-test contamination via the shared project fixture.
        settingsState.loadState(DopplerSettingsState.State())

        configurable = DopplerSettingsConfigurable(projectFixture.get())
        // createComponent() builds the Swing hierarchy and fires loadProjectsAsync()
        // in the background. The async fetch runs on a pooled thread and will fail
        // silently (no real CLI in test env), leaving the combo boxes empty — that is
        // the expected behaviour for these logic-focused tests.
        configurable.createComponent()
        // reset() writes the current (default) settings into the panel. The
        // selectedProject/selectedConfig setters append the persisted slug to the
        // model immediately, so isModified() reads correctly without waiting for the
        // async project-list load to complete.
        configurable.reset()
    }

    // ── isModified ─────────────────────────────────────────────────────────────

    @Test
    fun `isModified returns false when panel matches default settings after reset`() {
        assertThat(configurable.isModified).isFalse()
    }

    @Test
    fun `isModified returns true when settings change after the last reset`() {
        // Panel was reset with DEFAULT_CACHE_TTL_SECONDS (60).
        // Changing the persisted state without calling reset() means the panel
        // still shows 60 while settings say 999 → isModified must be true.
        settingsState.state.cacheTtlSeconds = 999
        assertThat(configurable.isModified).isTrue()
    }

    @Test
    fun `isModified returns false after reset syncs updated settings into panel`() {
        settingsState.state.cacheTtlSeconds = 120
        assertThat(configurable.isModified).isTrue() // panel still shows 60

        configurable.reset() // panel now shows 120
        assertThat(configurable.isModified).isFalse()
    }

    // ── apply ──────────────────────────────────────────────────────────────────

    @Test
    fun `apply writes all panel fields back to settings state`() {
        // Arrange: load non-default values into settings, sync to panel.
        settingsState.state.apply {
            enabled = false
            dopplerProject = "api-service"
            dopplerConfig = "staging"
            cacheTtlSeconds = 300
            cliPath = "/opt/homebrew/bin/doppler"
        }
        configurable.reset() // panel now mirrors those values

        // Act: reset settings to defaults, then apply panel state back.
        settingsState.loadState(DopplerSettingsState.State())
        configurable.apply()

        // Assert: settings reflect what the panel held.
        val s = settingsState.state
        assertThat(s.enabled).isFalse()
        assertThat(s.dopplerProject).isEqualTo("api-service")
        assertThat(s.dopplerConfig).isEqualTo("staging")
        assertThat(s.cacheTtlSeconds).isEqualTo(300)
        assertThat(s.cliPath).isEqualTo("/opt/homebrew/bin/doppler")
    }

    @Test
    fun `apply followed by isModified returns false`() {
        settingsState.state.cliPath = "/custom/path/doppler"
        configurable.reset()

        // Panel and settings agree → apply is a no-op, isModified stays false.
        configurable.apply()
        assertThat(configurable.isModified).isFalse()
    }

    // ── reset ──────────────────────────────────────────────────────────────────

    @Test
    fun `reset loads persisted enabled flag into panel`() {
        settingsState.state.enabled = false
        configurable.reset()
        // isModified being false implies the panel read the flag correctly.
        assertThat(configurable.isModified).isFalse()

        // Double-check by flipping settings to true and resetting again.
        settingsState.state.enabled = true
        configurable.reset()
        assertThat(configurable.isModified).isFalse()
    }

    // ── disposeUIResources ─────────────────────────────────────────────────────

    @Test
    fun `isModified returns false after disposeUIResources even if settings differ`() {
        // Create a detectable difference.
        settingsState.state.cacheTtlSeconds = 999
        assertThat(configurable.isModified).isTrue()

        // Dispose panel reference → isModified must return false regardless.
        configurable.disposeUIResources()
        assertThat(configurable.isModified).isFalse()
    }

    @Test
    fun `apply after disposeUIResources is a no-op and does not throw`() {
        settingsState.state.cacheTtlSeconds = 42
        configurable.disposeUIResources()
        // Should not throw; settings remain unchanged.
        configurable.apply()
        assertThat(settingsState.state.cacheTtlSeconds).isEqualTo(42)
    }
}

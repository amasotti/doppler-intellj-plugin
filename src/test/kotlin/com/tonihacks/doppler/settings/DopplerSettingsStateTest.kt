package com.tonihacks.doppler.settings

import com.intellij.util.xmlb.XmlSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DopplerSettingsStateTest {

    @Test
    fun `default state has sensible values`() {
        val settings = DopplerSettingsState()
        assertThat(settings.state.enabled).isTrue()
        assertThat(settings.state.dopplerProject).isEmpty()
        assertThat(settings.state.dopplerConfig).isEmpty()
        assertThat(settings.state.cacheTtlSeconds).isEqualTo(DopplerSettingsState.DEFAULT_CACHE_TTL_SECONDS)
        assertThat(settings.state.cliPath).isEmpty()
    }

    @Test
    fun `state survives XML round-trip preserving every field`() {
        // Verifies the actual PersistentStateComponent contract: serialize → XML → deserialize.
        // Catches binding mistakes (typo'd field name, wrong type) that unit-only tests miss.
        val original = DopplerSettingsState.State(
            enabled = false,
            dopplerProject = "my-service",
            dopplerConfig = "dev",
            cacheTtlSeconds = 120,
            cliPath = "/usr/local/bin/doppler",
        )

        val element = XmlSerializer.serialize(original)
        val roundtripped = XmlSerializer.deserialize(element, DopplerSettingsState.State::class.java)

        assertThat(roundtripped).isEqualTo(original)
    }

    @Test
    fun `isConfigured is false on a fresh instance`() {
        assertThat(DopplerSettingsState().isConfigured).isFalse()
    }

    @Test
    fun `isConfigured is false when project is blank`() {
        val settings = DopplerSettingsState()
        settings.state.enabled = true
        settings.state.dopplerProject = ""
        settings.state.dopplerConfig = "dev"
        assertThat(settings.isConfigured).isFalse()
    }

    @Test
    fun `isConfigured is false when config is blank`() {
        val settings = DopplerSettingsState()
        settings.state.enabled = true
        settings.state.dopplerProject = "my-service"
        settings.state.dopplerConfig = ""
        assertThat(settings.isConfigured).isFalse()
    }

    @Test
    fun `isConfigured is false when disabled even if project and config are set`() {
        val settings = DopplerSettingsState()
        settings.state.enabled = false
        settings.state.dopplerProject = "my-service"
        settings.state.dopplerConfig = "dev"
        assertThat(settings.isConfigured).isFalse()
    }

    @Test
    fun `isConfigured is true when enabled with project and config set`() {
        val settings = DopplerSettingsState()
        settings.state.enabled = true
        settings.state.dopplerProject = "my-service"
        settings.state.dopplerConfig = "dev"
        assertThat(settings.isConfigured).isTrue()
    }

    @Test
    fun `loadState replaces current state in full`() {
        val settings = DopplerSettingsState()
        settings.state.dopplerProject = "old-project"

        val loaded = DopplerSettingsState.State(
            enabled = false,
            dopplerProject = "loaded-project",
            dopplerConfig = "stg",
            cacheTtlSeconds = 120,
            cliPath = "/usr/local/bin/doppler",
        )
        settings.loadState(loaded)

        assertThat(settings.state.enabled).isFalse()
        assertThat(settings.state.dopplerProject).isEqualTo("loaded-project")
        assertThat(settings.state.dopplerConfig).isEqualTo("stg")
        assertThat(settings.state.cacheTtlSeconds).isEqualTo(120)
        assertThat(settings.state.cliPath).isEqualTo("/usr/local/bin/doppler")
    }

    @Test
    fun `getState reflects in-place mutation through state property`() {
        val settings = DopplerSettingsState()
        settings.state.cacheTtlSeconds = 30
        assertThat(settings.getState().cacheTtlSeconds).isEqualTo(30)
    }
}

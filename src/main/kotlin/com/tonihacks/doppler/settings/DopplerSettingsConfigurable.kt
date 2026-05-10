package com.tonihacks.doppler.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.tonihacks.doppler.DopplerBundle
import javax.swing.JComponent

/** Settings → Tools → Doppler. The platform constructs this with the current [Project]. */
class DopplerSettingsConfigurable(private val project: Project) : Configurable {

    private var panel: DopplerSettingsPanel? = null

    override fun getDisplayName(): String = DopplerBundle.message("settings.title")

    override fun createComponent(): JComponent {
        val p = DopplerSettingsPanel(project)
        panel = p
        p.loadProjectsAsync()
        return p.component
    }

    override fun isModified(): Boolean {
        val p = panel ?: return false
        val s = DopplerSettingsState.getInstance(project).state
        return p.isEnabled != s.enabled ||
            p.selectedProject != s.dopplerProject ||
            p.selectedConfig != s.dopplerConfig ||
            p.cacheTtlSeconds != s.cacheTtlSeconds ||
            p.cliPath != s.cliPath
    }

    override fun apply() {
        val p = panel ?: return
        val s = DopplerSettingsState.getInstance(project).state
        s.enabled = p.isEnabled
        s.dopplerProject = p.selectedProject
        s.dopplerConfig = p.selectedConfig
        s.cacheTtlSeconds = p.cacheTtlSeconds
        s.cliPath = p.cliPath
    }

    override fun reset() {
        val p = panel ?: return
        val s = DopplerSettingsState.getInstance(project).state
        p.isEnabled = s.enabled
        p.selectedProject = s.dopplerProject
        p.selectedConfig = s.dopplerConfig
        p.cacheTtlSeconds = s.cacheTtlSeconds
        p.cliPath = s.cliPath
        // Refresh project list in case CLI path changed.
        p.loadProjectsAsync()
    }

    override fun disposeUIResources() {
        panel = null
    }
}

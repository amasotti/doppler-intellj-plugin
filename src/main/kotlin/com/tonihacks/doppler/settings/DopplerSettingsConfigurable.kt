package com.tonihacks.doppler.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.tonihacks.doppler.DopplerBundle
import javax.swing.JComponent

/**
 * IntelliJ settings page under **Settings → Tools → Doppler**.
 *
 * Registered in `plugin.xml` as a `<projectConfigurable>` with
 * `nonDefaultProject="true"` so it is absent from the synthetic default project
 * and `parentId="tools"` so it appears under the Tools group.
 *
 * IntelliJ Platform injects the current [Project] into the constructor when it
 * instantiates this class from the `<projectConfigurable instance="...">` element.
 *
 * **Lifecycle:** [createComponent] is called once per dialog open; the returned
 * [JComponent] is cached by IntelliJ. [disposeUIResources] nulls the panel reference
 * so GC can collect the Swing tree when the dialog closes.
 *
 * **State flow:**
 * - [reset] → reads [DopplerSettingsState] → writes to [DopplerSettingsPanel]
 * - [isModified] → compares [DopplerSettingsPanel] to [DopplerSettingsState]
 * - [apply] → reads [DopplerSettingsPanel] → writes to [DopplerSettingsState]
 */
class DopplerSettingsConfigurable(private val project: Project) : Configurable {

    private var panel: DopplerSettingsPanel? = null

    override fun getDisplayName(): String = DopplerBundle.message("settings.title")

    override fun createComponent(): JComponent {
        val p = DopplerSettingsPanel(project)
        panel = p
        // Kick off async project loading; results populate the combo when they arrive.
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
        // Refresh the project list from the CLI in case the CLI path changed.
        p.loadProjectsAsync()
    }

    override fun disposeUIResources() {
        panel = null
    }
}

package com.tonihacks.doppler.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.tonihacks.doppler.DopplerBundle
import com.tonihacks.doppler.cli.DopplerCliClient
import com.tonihacks.doppler.cli.DopplerResult
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Swing panel for Settings → Tools → Doppler.
 *
 * `project` is a constructor parameter (not `val`) — capturing it as a field, or
 * inside an `invokeLater` lambda, would keep it alive in the FlushQueue past dialog
 * close and trip IntelliJ's project-leak detector. All async lambdas capture only
 * primitive snapshots and direct component references.
 *
 * `settings/` → `cli/` is a deliberate exception to the layer rules: combos are
 * populated directly from the CLI, bypassing `DopplerProjectService`, to avoid
 * widening that service's contract for this single UI need.
 */
class DopplerSettingsPanel(project: Project) {

    private val enabledCheckBox = JBCheckBox(DopplerBundle.message("settings.enabled"))

    companion object {
        private val LOG = Logger.getInstance(DopplerSettingsPanel::class.java)
    }
    private val projectCombo = ComboBox<String>()
    private val configCombo = ComboBox<String>()
    private val ttlSpinner = JSpinner(
        SpinnerNumberModel(DopplerSettingsState.DEFAULT_CACHE_TTL_SECONDS, 0, 3600, 10),
    )
    private val cliPathField = TextFieldWithBrowseButton()
    private val statusLabel = JBLabel()

    // Set during model swap so the item listener ignores the auto-fired SELECTED event.
    private var updatingModel = false

    val component: JComponent

    init {
        cliPathField.addBrowseFolderListener(
            "Select Doppler CLI binary",
            null,
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
        )

        projectCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED && !updatingModel) {
                val slug = e.item as? String ?: return@addItemListener
                if (slug.isNotBlank()) loadConfigsAsync(slug)
            }
        }
        component = buildPanel()
    }

    var isEnabled: Boolean
        get() = enabledCheckBox.isSelected
        set(value) { enabledCheckBox.isSelected = value }

    /** Empty when nothing selected. Setter appends the value if absent so a persisted slug
     *  is visible before the async list arrives. */
    var selectedProject: String
        get() = projectCombo.selectedItem as? String ?: ""
        set(value) {
            val model = projectCombo.model as DefaultComboBoxModel<String>
            if (value.isNotBlank() && model.getIndexOf(value) < 0) model.addElement(value)
            projectCombo.selectedItem = value.ifBlank { null }
        }

    var selectedConfig: String
        get() = configCombo.selectedItem as? String ?: ""
        set(value) {
            val model = configCombo.model as DefaultComboBoxModel<String>
            if (value.isNotBlank() && model.getIndexOf(value) < 0) model.addElement(value)
            configCombo.selectedItem = value.ifBlank { null }
        }

    var cacheTtlSeconds: Int
        get() = (ttlSpinner.value as? Number)?.toInt()
            ?: DopplerSettingsState.DEFAULT_CACHE_TTL_SECONDS
        set(value) { ttlSpinner.value = value }

    /** Empty = resolve via PATH. */
    var cliPath: String
        get() = cliPathField.text.trim()
        set(value) { cliPathField.text = value }

    /** EDT entry point. Snapshots state, dispatches to a pool, marshals back via invokeLater. */
    @Suppress("TooGenericExceptionCaught")
    fun loadProjectsAsync() {
        val currentCliPath = cliPath
        val currentSelection = selectedProject
        val combo = projectCombo
        LOG.info("DopplerSettingsPanel: loadProjectsAsync dispatching, cliPath='$currentCliPath'")
        ApplicationManager.getApplication().executeOnPooledThread {
            LOG.info("DopplerSettingsPanel: loadProjectsAsync lambda started")
            try {
                val cli = DopplerCliClient(cliPath = currentCliPath.takeIf { it.isNotBlank() })
                when (val result = cli.listProjects()) {
                    is DopplerResult.Success -> {
                        val slugs = result.value.map { it.slug }
                        LOG.info("DopplerSettingsPanel: listProjects success, ${slugs.size} projects: $slugs")
                        ApplicationManager.getApplication().invokeLater({
                            updateCombo(combo, slugs, preserveSelection = currentSelection)
                            LOG.info("DopplerSettingsPanel: projectCombo updated")
                        }, ModalityState.any())
                    }
                    is DopplerResult.Failure -> LOG.warn("DopplerSettingsPanel: listProjects failed: ${result.error}")
                }
            } catch (e: Exception) {
                LOG.error("DopplerSettingsPanel: loadProjectsAsync failed", e)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadConfigsAsync(projectSlug: String) {
        val currentCliPath = cliPath
        val currentSelection = selectedConfig
        val combo = configCombo
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val cli = DopplerCliClient(cliPath = currentCliPath.takeIf { it.isNotBlank() })
                when (val result = cli.listConfigs(projectSlug)) {
                    is DopplerResult.Success -> {
                        val names = result.value.map { it.name }
                        ApplicationManager.getApplication().invokeLater({
                            updateCombo(combo, names, preserveSelection = currentSelection)
                        }, ModalityState.any())
                    }
                    is DopplerResult.Failure -> Unit
                }
            } catch (e: Exception) {
                LOG.error("DopplerSettingsPanel: loadConfigsAsync failed", e)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun testConnection() {
        statusLabel.text = DopplerBundle.message("settings.test.connection.testing")
        val currentCliPath = cliPath
        val label = statusLabel
        ApplicationManager.getApplication().executeOnPooledThread {
            LOG.info("DopplerSettingsPanel: testConnection lambda started, cliPath='$currentCliPath'")
            try {
                val cli = DopplerCliClient(cliPath = currentCliPath.takeIf { it.isNotBlank() })
                val versionResult = cli.version()
                LOG.info("DopplerSettingsPanel: version result=$versionResult")
                val meResult = cli.me()
                LOG.info("DopplerSettingsPanel: me result=$meResult")
                val text = buildStatusText(versionResult, meResult)
                ApplicationManager.getApplication().invokeLater({
                    label.text = text
                }, ModalityState.any())
            } catch (e: Exception) {
                LOG.error("DopplerSettingsPanel: testConnection failed", e)
                val msg = "Error: ${e.javaClass.simpleName}: ${e.message}"
                ApplicationManager.getApplication().invokeLater({
                    label.text = msg
                }, ModalityState.any())
            }
        }
    }

    private fun buildStatusText(
        versionResult: DopplerResult<String>,
        meResult: DopplerResult<com.tonihacks.doppler.cli.DopplerUser>,
    ): String = when {
        versionResult is DopplerResult.Success && meResult is DopplerResult.Success ->
            DopplerBundle.message(
                "settings.test.connection.success",
                meResult.value.email.ifBlank { meResult.value.name },
                versionResult.value,
            )
        versionResult is DopplerResult.Failure ->
            DopplerBundle.message("settings.test.connection.failure", versionResult.error)
        meResult is DopplerResult.Failure ->
            DopplerBundle.message("settings.test.connection.failure", meResult.error)
        else -> DopplerBundle.message("settings.test.connection.failure", "Unknown error")
    }

    private fun buildPanel(): JComponent = panel {
        row { cell(enabledCheckBox) }
        separator()
        row(DopplerBundle.message("settings.project.label")) {
            cell(projectCombo).align(AlignX.FILL)
        }
        row(DopplerBundle.message("settings.config.label")) {
            cell(configCombo).align(AlignX.FILL)
        }
        separator()
        row(DopplerBundle.message("settings.cache.ttl")) {
            cell(ttlSpinner)
        }
        row(DopplerBundle.message("settings.cli.path")) {
            cell(cliPathField).align(AlignX.FILL)
        }
        separator()
        row {
            button(DopplerBundle.message("settings.test.connection")) { testConnection() }
            cell(statusLabel)
        }
    }

    /**
     * Replaces the model and restores the prior selection if still present.
     * Leaves the combo unselected otherwise — auto-selecting the first item would
     * fire a spurious `loadConfigsAsync` for the wrong project. EDT only.
     */
    private fun updateCombo(combo: ComboBox<String>, items: List<String>, preserveSelection: String) {
        updatingModel = true
        try {
            combo.model = DefaultComboBoxModel(items.toTypedArray())
            combo.selectedItem = if (preserveSelection.isNotBlank() && items.contains(preserveSelection)) {
                preserveSelection
            } else {
                null
            }
        } finally {
            updatingModel = false
        }
    }
}

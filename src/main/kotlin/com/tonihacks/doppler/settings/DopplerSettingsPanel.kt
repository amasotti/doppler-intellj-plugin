package com.tonihacks.doppler.settings

import com.intellij.openapi.application.ApplicationManager
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
 * Swing panel for **Settings → Tools → Doppler**.
 *
 * All UI state is held in the component references ([enabledCheckBox],
 * [projectCombo], etc.). The owning [DopplerSettingsConfigurable] reads and
 * writes through the accessor properties ([isEnabled], [selectedProject],
 * [selectedConfig], [cacheTtlSeconds], [cliPath]).
 *
 * ## Layer note
 *
 * This class instantiates [DopplerCliClient] directly (bypassing
 * [com.tonihacks.doppler.service.DopplerProjectService]) to populate the
 * project / config combo boxes. The alternative — adding `listProjects` /
 * `listConfigs` to `DopplerProjectService` — would expand Phase 5's scope
 * and create a reverse dependency from `service/` callers into this UI. The
 * `settings/` → `cli/` dependency is documented here as a deliberate exception;
 * `settings/DopplerSettingsState` (the state class) retains zero deps on `cli/`.
 *
 * ## No [Project] field retained after construction
 *
 * [project] is a plain constructor parameter (not `val`) used only in the
 * `init` block to wire up the CLI-path browse listener. All `invokeLater`
 * lambdas that are queued in the EDT `FlushQueue` capture only Swing component
 * references (not `this` or the project). This prevents the project from being
 * held alive in the queue after the settings dialog is closed, which would
 * otherwise trigger IntelliJ's project-leak detector.
 *
 * ## Threading
 *
 * [loadProjectsAsync] and [testConnection] dispatch to a pooled thread and
 * post results back to the EDT via `invokeLater`. All Swing-component state
 * reads are snapshotted on the EDT **before** dispatching to the pool. The
 * `invokeLater` lambdas capture only immutable `String` values and direct
 * combo-box references (not `this`), so the FlushQueue does not retain a path
 * back to the project once the lambda completes.
 *
 * ## Security
 *
 * The "Test connection" result shown in [statusLabel] is either
 * `email + CLI version` on success or the CLI's stderr on failure. The two
 * commands used (`doppler --version` and `doppler me`) do not print secret
 * values in their output — `--version` prints only a version string and `me`
 * prints user metadata. This guarantee rests on the Doppler CLI's documented
 * behaviour, not on code enforcement; see the Phase 7/8 carry-forward TODO in
 * [com.tonihacks.doppler.service.DopplerFetchException] for a planned
 * redactor that would make it code-enforced.
 *
 * No secret value touches any label, text field, or combo box in this panel.
 */
class DopplerSettingsPanel(project: Project) { // no `val` — not stored as a field

    // Components declared before the panel builder so the DSL lambdas can
    // reference them directly.
    private val enabledCheckBox = JBCheckBox(DopplerBundle.message("settings.enabled"))
    private val projectCombo = ComboBox<String>()
    private val configCombo = ComboBox<String>()
    private val ttlSpinner = JSpinner(
        SpinnerNumberModel(DopplerSettingsState.DEFAULT_CACHE_TTL_SECONDS, 0, 3600, 10),
    )
    private val cliPathField = TextFieldWithBrowseButton()
    private val statusLabel = JBLabel()

    /**
     * Guard flag set during [updateCombo] model replacement.
     *
     * Replacing `projectCombo.model` fires an `ItemEvent.SELECTED` for the
     * first item in the new model — we do not want that event to trigger a
     * `loadConfigsAsync` call for an auto-selected item. The item listener
     * checks this flag and skips the load while a model swap is in progress.
     *
     * Access: EDT only (item listener + [updateCombo] both run on EDT).
     */
    private var updatingModel = false

    /** The root [JComponent] to return from [DopplerSettingsConfigurable.createComponent]. */
    val component: JComponent

    init {
        // Wire up the browse listener using project only here; not stored in a field.
        cliPathField.addBrowseFolderListener(
            "Select Doppler CLI binary",
            null,
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
        )

        // Load configs whenever the user changes the project selection.
        // Skip loads triggered by [updateCombo]'s model replacement.
        projectCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED && !updatingModel) {
                val slug = e.item as? String ?: return@addItemListener
                if (slug.isNotBlank()) loadConfigsAsync(slug)
            }
        }
        component = buildPanel()
    }

    // ── Accessors for DopplerSettingsConfigurable ──────────────────────────────

    var isEnabled: Boolean
        get() = enabledCheckBox.isSelected
        set(value) { enabledCheckBox.isSelected = value }

    /**
     * Selected Doppler project slug; empty string when nothing is selected.
     *
     * Setting a value that is not already in the combo's model appends it first
     * so that a persisted slug is visible even before the async project list loads.
     */
    var selectedProject: String
        get() = projectCombo.selectedItem as? String ?: ""
        set(value) {
            val model = projectCombo.model as DefaultComboBoxModel<String>
            if (value.isNotBlank() && model.getIndexOf(value) < 0) model.addElement(value)
            projectCombo.selectedItem = value.ifBlank { null }
        }

    /** Selected Doppler config name; empty string when nothing is selected. */
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

    /** Custom CLI path; empty string means "resolve via PATH". */
    var cliPath: String
        get() = cliPathField.text.trim()
        set(value) { cliPathField.text = value }

    // ── Async loading ──────────────────────────────────────────────────────────

    /**
     * Fetches the Doppler project list in a background thread and populates
     * [projectCombo]. Silently no-ops when the CLI is unavailable or not
     * authenticated — the user can diagnose that via the "Test connection" button.
     *
     * Must be called from the EDT. All Swing-state reads are snapshotted here
     * before dispatching to the pool so that background code never touches
     * Swing components.
     */
    fun loadProjectsAsync() {
        // Snapshot all EDT state before dispatching to the pool.
        val currentCliPath = cliPath
        val currentSelection = selectedProject
        val combo = projectCombo
        ApplicationManager.getApplication().executeOnPooledThread {
            val cli = DopplerCliClient(cliPath = currentCliPath.takeIf { it.isNotBlank() })
            when (val result = cli.listProjects()) {
                is DopplerResult.Success -> {
                    val slugs = result.value.map { it.slug }
                    // Lambda captures only primitives and the combo reference —
                    // not `this` — so the FlushQueue does not retain the panel.
                    ApplicationManager.getApplication().invokeLater {
                        updateCombo(combo, slugs, preserveSelection = currentSelection)
                    }
                }
                is DopplerResult.Failure -> Unit // leave combo empty; user can test connection
            }
        }
    }

    private fun loadConfigsAsync(projectSlug: String) {
        val currentCliPath = cliPath
        val currentSelection = selectedConfig
        val combo = configCombo
        ApplicationManager.getApplication().executeOnPooledThread {
            val cli = DopplerCliClient(cliPath = currentCliPath.takeIf { it.isNotBlank() })
            when (val result = cli.listConfigs(projectSlug)) {
                is DopplerResult.Success -> {
                    val names = result.value.map { it.name }
                    ApplicationManager.getApplication().invokeLater {
                        updateCombo(combo, names, preserveSelection = currentSelection)
                    }
                }
                is DopplerResult.Failure -> Unit
            }
        }
    }

    // ── Test connection ────────────────────────────────────────────────────────

    private fun testConnection() {
        statusLabel.text = DopplerBundle.message("settings.test.connection.testing")
        val currentCliPath = cliPath
        val label = statusLabel
        ApplicationManager.getApplication().executeOnPooledThread {
            val cli = DopplerCliClient(cliPath = currentCliPath.takeIf { it.isNotBlank() })
            val versionResult = cli.version()
            val meResult = cli.me()
            val text = buildStatusText(versionResult, meResult)
            ApplicationManager.getApplication().invokeLater {
                label.text = text
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
                meResult.value.email,
                versionResult.value,
            )
        versionResult is DopplerResult.Failure ->
            DopplerBundle.message("settings.test.connection.failure", versionResult.error)
        meResult is DopplerResult.Failure ->
            DopplerBundle.message("settings.test.connection.failure", meResult.error)
        // Sealed class is exhaustive — DopplerResult has only Success and Failure.
        // The compiler does not know this in a `when` expression without an `else`
        // because the sealed type argument is covariant. Unreachable in practice.
        else -> DopplerBundle.message("settings.test.connection.failure", "Unknown error")
    }

    // ── Private layout helpers ─────────────────────────────────────────────────

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
     * Replaces [combo]'s model with [items], restoring the previous selection
     * if [preserveSelection] is still present in the new list. If the persisted
     * selection is absent from the new list (e.g., CLI auth expired or different
     * workspace), the combo is left with no selection rather than auto-selecting
     * the first item — which would otherwise trigger a spurious
     * `loadConfigsAsync` call for the wrong project.
     *
     * [updatingModel] is set for the duration of the call so the [projectCombo]
     * item listener ignores the model-replacement events.
     *
     * **Must be called on the EDT.**
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

package com.tonihacks.doppler.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.tonihacks.doppler.DopplerBundle
import com.tonihacks.doppler.cli.DopplerCliClient
import com.tonihacks.doppler.cli.DopplerResult
import com.tonihacks.doppler.notification.DopplerNotifier
import com.tonihacks.doppler.service.DopplerFetchException
import com.tonihacks.doppler.service.DopplerProjectService
import com.tonihacks.doppler.settings.DopplerSettingsState
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextField

/**
 * Swing panel embedded in the **Doppler** tool window.
 *
 * Displays all secrets for the currently configured Doppler project/config in a
 * `JBTable` with masking, reveal/hide toggle, inline editing, and save/delete actions.
 *
 * ## Threading
 *
 * [loadSecretsAsync] and [saveChangesAsync] dispatch to a pooled thread via
 * `executeOnPooledThread`. All Swing mutations go through `invokeLater`. EDT state is
 * snapshotted before pool dispatch — background code never touches Swing components.
 *
 * ## Security
 *
 * - Secret values never appear in any [LOG] call.
 * - Secret values never appear in notification bodies sent via [notifyError] / [notifyInfo]
 *   — failure notifications report only the key name, never CLI stderr or a value.
 * - "Copy Value" writes to the system clipboard only — no log, no notification body.
 * - [performSave] passes values via [DopplerCliClient.setSecret] stdin, never as argv.
 *
 * ## Injection
 *
 * [cliFactory], [notifyError], and [notifyInfo] are defaulted functional parameters that
 * can be overridden in tests without a mocking library (same pattern as
 * [com.tonihacks.doppler.injection.gradle.DopplerGradleRunConfigurationExtension]).
 */
@Suppress("TooManyFunctions")
class DopplerToolWindowPanel(
    private val project: Project,
    private val cliFactory: () -> DopplerCliClient = { defaultCli(project) },
    private val notifyError: (Project, String) -> Unit = DopplerNotifier::notifyError,
    private val notifyInfo: (Project, String) -> Unit = DopplerNotifier::notifyInfo,
) : JPanel(BorderLayout()) {

    companion object {
        private val LOG = Logger.getInstance(DopplerToolWindowPanel::class.java)

        private fun defaultCli(project: Project): DopplerCliClient {
            val cliPath = DopplerSettingsState.getInstance(project).state.cliPath
            return DopplerCliClient(cliPath = cliPath.takeIf { it.isNotBlank() })
        }
    }

    internal val model = SecretsTableModel()
    private val table = JBTable(model)
    internal val statusLabel = JBLabel(DopplerBundle.message("toolwindow.status.loading"))
    internal val saveButton = JButton(DopplerBundle.message("toolwindow.save")).also { it.isEnabled = false }

    init {
        setupLayout()
        // Single source of truth for save-button state: the model listener.
        model.addTableModelListener { saveButton.isEnabled = model.hasModifiedRows() }
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e)
        })
        saveButton.addActionListener { saveChangesAsync() }
    }

    private fun setupLayout() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        val refreshButton = JButton(DopplerBundle.message("toolwindow.refresh"))
        val addButton = JButton(DopplerBundle.message("toolwindow.add"))
        refreshButton.addActionListener { loadSecretsAsync() }
        addButton.addActionListener { showAddDialog() }
        toolbar.add(refreshButton)
        toolbar.add(addButton)

        val south = JPanel(BorderLayout())
        south.add(statusLabel, BorderLayout.WEST)
        south.add(saveButton, BorderLayout.EAST)

        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Fetches secrets in the background and populates the table on success.
     *
     * Must be called from the EDT (e.g., button listener, tool window init).
     */
    fun loadSecretsAsync() {
        statusLabel.text = DopplerBundle.message("toolwindow.status.loading")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val secrets = DopplerProjectService.getInstance(project).fetchSecrets()
                ApplicationManager.getApplication().invokeLater({
                    applyLoadedSecrets(secrets)
                }, ModalityState.any())
            } catch (e: DopplerFetchException) {
                // Log only the exception type — never e.message, which is CLI stderr and
                // might contain key names (not values in normal operation, but defensive).
                LOG.warn("DopplerToolWindowPanel: fetch failed (${e.javaClass.simpleName})")
                // e.message is String (non-nullable) — DopplerFetchException overrides val message: String
                ApplicationManager.getApplication().invokeLater({
                    applyFetchError(e.message)
                    notifyError(project, e.message)
                }, ModalityState.any())
            }
        }
    }

    // ── Internal: synchronous counterparts (exposed for testing) ──────────────

    /**
     * Replaces the table contents with [secrets], sorted by key.
     *
     * Must be called on the EDT. [setRows] fires [fireTableDataChanged], which
     * triggers the [addTableModelListener] — that listener sets `saveButton.isEnabled`
     * based on `model.hasModifiedRows()`. No direct `saveButton.isEnabled` mutation
     * here so there is a single source of truth.
     */
    internal fun applyLoadedSecrets(secrets: Map<String, String>) {
        val rows = secrets.entries.sortedBy { it.key }.map { SecretRow(it.key, it.value) }
        model.setRows(rows)
        statusLabel.text = DopplerBundle.message("toolwindow.status.refreshed")
    }

    /**
     * Sets the status label to an error message containing [msg].
     *
     * Must be called on the EDT. [msg] must never contain a secret value (it is
     * CLI stderr verbatim — see [DopplerFetchException] contract).
     */
    internal fun applyFetchError(msg: String) {
        statusLabel.text = DopplerBundle.message("toolwindow.status.error", msg)
    }

    /**
     * Calls [DopplerCliClient.setSecret] for each row synchronously.
     *
     * Returns the list of keys whose save failed. The caller ([saveChangesAsync])
     * handles per-key failure notifications so this method stays notification-free.
     *
     * Must be called on a background thread. Values are passed via CLI stdin — never
     * as argv (per spec §6.2 + §11.6).
     */
    internal fun performSave(
        cli: DopplerCliClient,
        proj: String,
        cfg: String,
        rows: List<SecretRow>,
    ): List<String> {
        val failed = mutableListOf<String>()
        rows.forEach { row ->
            when (cli.setSecret(proj, cfg, row.key, row.value)) {
                is DopplerResult.Success -> Unit
                is DopplerResult.Failure -> failed += row.key
            }
        }
        return failed
    }

    // ── Private: async save + edit actions ────────────────────────────────────

    private fun saveChangesAsync() {
        // Snapshot modified rows on EDT before dispatching to background.
        // `.map { it.copy() }` creates data-class copies so the background thread reads
        // immutable snapshots — prevents a data race if the user edits another cell while
        // a save is in flight.
        val toSave = model.rows.filter { it.modified }.map { it.copy() }
        val s = DopplerSettingsState.getInstance(project).state
        val proj = s.dopplerProject
        val cfg = s.dopplerConfig
        if (toSave.isEmpty() || proj.isBlank() || cfg.isBlank()) return

        ApplicationManager.getApplication().executeOnPooledThread {
            val cli = cliFactory()
            val failedKeys = performSave(cli, proj, cfg, toSave)
            val savedCount = toSave.size - failedKeys.size

            ApplicationManager.getApplication().invokeLater({
                // Failure notification uses only the key name — never CLI stderr or the
                // value, which could contain secret content.
                failedKeys.forEach { key ->
                    notifyError(project, DopplerBundle.message("notification.save.failure", key))
                }
                if (savedCount > 0) {
                    DopplerProjectService.getInstance(project).invalidateCache()
                    notifyInfo(
                        project,
                        DopplerBundle.message("notification.save.success", savedCount, proj, cfg),
                    )
                }
                loadSecretsAsync()
            }, ModalityState.any())
        }
    }

    @Suppress("ReturnCount")
    private fun showAddDialog() {
        val nameField = JTextField(20)
        val valueField = JTextField(20)
        // GridLayout(rows, cols) keeps label and field on the same row.
        val form = JPanel(GridLayout(2, 2, 4, 4)).apply {
            add(JLabel(DopplerBundle.message("toolwindow.add.dialog.name")))
            add(nameField)
            add(JLabel(DopplerBundle.message("toolwindow.add.dialog.value")))
            add(valueField)
        }
        val choice = JOptionPane.showConfirmDialog(
            this,
            form,
            DopplerBundle.message("toolwindow.add"),
            JOptionPane.OK_CANCEL_OPTION,
        )
        if (choice != JOptionPane.OK_OPTION) return

        val key = nameField.text.trim()
        // Snapshot value on EDT before dispatching — value field is not referenced
        // from the background thread.
        val value = valueField.text
        if (key.isBlank()) return

        val s = DopplerSettingsState.getInstance(project).state
        val proj = s.dopplerProject
        val cfg = s.dopplerConfig
        if (proj.isBlank() || cfg.isBlank()) return

        ApplicationManager.getApplication().executeOnPooledThread {
            when (cliFactory().setSecret(proj, cfg, key, value)) {
                is DopplerResult.Success -> {
                    ApplicationManager.getApplication().invokeLater({
                        DopplerProjectService.getInstance(project).invalidateCache()
                        loadSecretsAsync()
                    }, ModalityState.any())
                }
                is DopplerResult.Failure -> {
                    // Use only the key name in the notification — not CLI stderr, which
                    // could contain a partial echo of the value being set.
                    ApplicationManager.getApplication().invokeLater({
                        notifyError(project, DopplerBundle.message("notification.save.failure", key))
                    }, ModalityState.any())
                }
            }
        }
    }

    private fun deleteSecretAsync(row: SecretRow) {
        val s = DopplerSettingsState.getInstance(project).state
        val proj = s.dopplerProject
        val cfg = s.dopplerConfig
        if (proj.isBlank() || cfg.isBlank()) return

        // Snapshot key on EDT before dispatching.
        val key = row.key
        ApplicationManager.getApplication().executeOnPooledThread {
            when (cliFactory().deleteSecret(proj, cfg, key)) {
                is DopplerResult.Success -> {
                    ApplicationManager.getApplication().invokeLater({
                        DopplerProjectService.getInstance(project).invalidateCache()
                        notifyInfo(project, DopplerBundle.message("notification.delete.success", key))
                        loadSecretsAsync()
                    }, ModalityState.any())
                }
                is DopplerResult.Failure -> {
                    // Use only the key name — not CLI stderr, which could be ambiguous.
                    ApplicationManager.getApplication().invokeLater({
                        notifyError(project, DopplerBundle.message("notification.delete.failure", key))
                    }, ModalityState.any())
                }
            }
        }
    }

    private fun maybeShowContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val rowIdx = table.rowAtPoint(e.point)
        if (rowIdx < 0) return
        table.selectionModel.setSelectionInterval(rowIdx, rowIdx)
        buildContextMenu(model.rows[rowIdx]).show(e.component, e.x, e.y)
    }

    private fun buildContextMenu(capturedRow: SecretRow): JPopupMenu {
        val menu = JPopupMenu()

        menu.add(DopplerBundle.message("toolwindow.context.copy.name")).addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(capturedRow.key))
        }

        // Value written to clipboard only — never to a log or notification body.
        menu.add(DopplerBundle.message("toolwindow.context.copy.value")).addActionListener {
            // Resolve the live row by key at action time — the captured row's value may
            // be stale if a background reload replaced the model between right-click and
            // the menu item click.
            val liveValue = model.rows.firstOrNull { it.key == capturedRow.key }?.value
                ?: capturedRow.value
            CopyPasteManager.getInstance().setContents(StringSelection(liveValue))
        }

        val revealLabel = if (capturedRow.revealed)
            DopplerBundle.message("toolwindow.context.hide")
        else
            DopplerBundle.message("toolwindow.context.reveal")
        menu.add(revealLabel).addActionListener {
            // Look up by key at action time — the model may have been refreshed between
            // right-click and the menu item click, so capturedRow may no longer be in _rows.
            val idx = model.rows.indexOfFirst { it.key == capturedRow.key }
            if (idx >= 0) {
                model.rows[idx].revealed = !model.rows[idx].revealed
                model.fireTableRowsUpdated(idx, idx)
            }
        }

        menu.add(DopplerBundle.message("toolwindow.context.delete")).addActionListener {
            val confirm = JOptionPane.showConfirmDialog(
                this@DopplerToolWindowPanel,
                DopplerBundle.message("toolwindow.delete.confirm", capturedRow.key),
                DopplerBundle.message("toolwindow.context.delete"),
                JOptionPane.OK_CANCEL_OPTION,
            )
            if (confirm == JOptionPane.OK_OPTION) deleteSecretAsync(capturedRow)
        }

        return menu
    }
}

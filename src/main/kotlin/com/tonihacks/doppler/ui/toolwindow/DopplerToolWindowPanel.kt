package com.tonihacks.doppler.ui.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.table.TableRowSorter
import com.tonihacks.doppler.DopplerBundle
import com.tonihacks.doppler.cli.DopplerCliClient
import com.tonihacks.doppler.cli.DopplerResult
import com.tonihacks.doppler.notification.DopplerNotifier
import com.tonihacks.doppler.service.DopplerFetchException
import com.tonihacks.doppler.service.DopplerProjectService
import com.tonihacks.doppler.settings.DopplerSettingsState
import com.tonihacks.doppler.ui.toolwindow.actions.AddSecretAction
import com.tonihacks.doppler.ui.toolwindow.actions.CopyValueAction
import com.tonihacks.doppler.ui.toolwindow.actions.DeleteSecretAction
import com.tonihacks.doppler.ui.toolwindow.actions.OpenSettingsAction
import com.tonihacks.doppler.ui.toolwindow.actions.RefreshAction
import com.tonihacks.doppler.ui.toolwindow.actions.RevealHideAction
import java.awt.BorderLayout
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
        private const val TOOLBAR_PLACE = "DopplerToolWindowToolbar"

        private fun defaultCli(project: Project): DopplerCliClient {
            val cliPath = DopplerSettingsState.getInstance(project).state.cliPath
            return DopplerCliClient(cliPath = cliPath.takeIf { it.isNotBlank() })
        }
    }

    internal val model = SecretsTableModel()
    private val sorter = TableRowSorter(model).apply {
        setSortable(SecretsTableModel.COL_NAME, false)
        setSortable(SecretsTableModel.COL_VALUE, false)
    }
    private val table = JBTable(model).also { it.rowSorter = sorter }
    private val searchField = SearchTextField(false).apply {
        toolTipText = DopplerBundle.message("toolwindow.search.tooltip")
    }
    internal val statusLabel = JBLabel(DopplerBundle.message("toolwindow.status.loading"))
    internal val saveButton = JButton(DopplerBundle.message("toolwindow.save")).also { it.isEnabled = false }

    /**
     * EDT-only flag indicating a `loadSecretsAsync` is in flight. Read by
     * [com.tonihacks.doppler.ui.toolwindow.actions.RefreshAction.update] to prevent
     * a second click from racing with an in-progress fetch (issue: refresh button
     * required multiple clicks to take effect because each click queued another
     * background task before the previous one finished).
     *
     * Mutated only on the EDT — both write sites are inside `loadSecretsAsync`
     * (entry guard) and inside `invokeLater` callbacks (completion / error). No
     * background-thread access, hence no `@Volatile`.
     */
    internal var isLoading: Boolean = false
        private set

    private lateinit var actionToolbar: ActionToolbar

    init {
        setupLayout()
        // Single source of truth for save-button state: the model listener.
        model.addTableModelListener { saveButton.isEnabled = model.hasModifiedRows() }
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applySearchFilter()
        })
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e)
        })
        // Selection drives enable-state of row-aware actions; nudge toolbar on every change.
        // Selection drives enable-state of row-aware actions. Filter `valueIsAdjusting`
        // so we update once per click instead of twice (Swing fires intermediate
        // adjusting events during a single selection change).
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) actionToolbar.updateActionsAsync()
        }
        saveButton.addActionListener { saveChangesAsync() }
    }

    private fun setupLayout() {
        val group = DefaultActionGroup().apply {
            add(RefreshAction(this@DopplerToolWindowPanel))
            add(AddSecretAction(this@DopplerToolWindowPanel))
            addSeparator()
            add(RevealHideAction(this@DopplerToolWindowPanel))
            add(CopyValueAction(this@DopplerToolWindowPanel))
            add(DeleteSecretAction(this@DopplerToolWindowPanel))
            add(Separator.getInstance())
            add(OpenSettingsAction(project))
        }
        actionToolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_PLACE, group, /* horizontal = */ true)
            .apply { targetComponent = this@DopplerToolWindowPanel }

        val south = JPanel(BorderLayout())
        south.add(statusLabel, BorderLayout.WEST)
        south.add(saveButton, BorderLayout.EAST)

        val north = JPanel(BorderLayout())
        north.add(actionToolbar.component, BorderLayout.NORTH)
        north.add(searchField, BorderLayout.SOUTH)

        add(north, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)
    }

    private fun applySearchFilter() {
        val text = searchField.text.trim()
        sorter.rowFilter = if (text.isBlank()) null
        else RowFilter.regexFilter("(?i)${Regex.escape(text)}", SecretsTableModel.COL_NAME)
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Fetches secrets in the background and populates the table on success.
     *
     * Must be called from the EDT (e.g., button listener, tool window init).
     *
     * Sets [isLoading] = true for the duration of the fetch. The Refresh action's
     * `update()` reads this flag and disables itself, so a user can't enqueue a
     * second fetch while the first is still running. Both completion paths
     * (success and failure) reset [isLoading] = false on the EDT.
     */
    fun loadSecretsAsync() {
        if (isLoading) return
        isLoading = true
        actionToolbar.updateActionsAsync()
        statusLabel.text = DopplerBundle.message("toolwindow.status.loading")
        ApplicationManager.getApplication().executeOnPooledThread {
            // Guarantee `isLoading` is reset on every exit path: success, expected
            // failure (DopplerFetchException), or unexpected RuntimeException. Without
            // this, a stray NPE / IllegalStateException from a future code path would
            // leave `isLoading = true` permanently, disabling Refresh for the rest of
            // the IDE session. ProcessCanceledException is rethrown unchanged per
            // IntelliJ Platform contract — it must reach the platform's cancellation
            // handler.
            var error: DopplerFetchException? = null
            var unexpected: Throwable? = null
            var secrets: Map<String, String>? = null
            try {
                secrets = DopplerProjectService.getInstance(project).fetchSecrets()
            } catch (e: ProcessCanceledException) {
                ApplicationManager.getApplication().invokeLater({
                    isLoading = false
                    actionToolbar.updateActionsAsync()
                }, ModalityState.any())
                throw e
            } catch (e: DopplerFetchException) {
                error = e
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                // Defensive last-resort catch so an unexpected RuntimeException
                // (e.g. NPE from a future fetchSecrets() refactor) cannot leave
                // `isLoading = true` forever and permanently disable Refresh.
                // ProcessCanceledException is already caught + rethrown above.
                unexpected = e
            }

            ApplicationManager.getApplication().invokeLater({
                try {
                    when {
                        secrets != null -> applyLoadedSecrets(secrets)
                        error != null -> {
                            // Never log e.message — it is CLI stderr verbatim. Log only the
                            // exception class name so leaks via log-shipping are impossible.
                            LOG.warn("DopplerToolWindowPanel: fetch failed (${error.javaClass.simpleName})")
                            applyFetchError(error.message)
                            notifyError(project, error.message)
                        }
                        unexpected != null -> {
                            // Unexpected throwable: log only class name (not message,
                            // not stack trace string — defensive against accidental
                            // value-bearing causes). Surface a generic message to user.
                            val cls = unexpected.javaClass.simpleName
                            LOG.warn("DopplerToolWindowPanel: fetch failed unexpectedly ($cls)")
                            val msg = DopplerBundle.message("toolwindow.status.unexpected.error")
                            applyFetchError(msg)
                            notifyError(project, msg)
                        }
                    }
                } finally {
                    isLoading = false
                    actionToolbar.updateActionsAsync()
                }
            }, ModalityState.any())
        }
    }

    // ── Internal: synchronous counterparts (exposed for testing) ──────────────

    /**
     * Replaces the table contents with [secrets], sorted by key.
     *
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

    // ── Internal: action hooks (called from AnAction.actionPerformed) ─────────

    /** EDT-only. Returns the [SecretRow] for the currently selected table row, or null. */
    internal fun selectedSecretRow(): SecretRow? {
        val viewIdx = table.selectedRow
        if (viewIdx < 0) return null
        val modelIdx = table.convertRowIndexToModel(viewIdx)
        if (modelIdx < 0 || modelIdx >= model.rowCount) return null
        return model.rows[modelIdx]
    }

    /** EDT-only. Toggles the reveal flag on the selected row and repaints. */
    internal fun toggleRevealOnSelected() {
        val viewIdx = table.selectedRow
        if (viewIdx < 0) return
        val modelIdx = table.convertRowIndexToModel(viewIdx)
        if (modelIdx < 0 || modelIdx >= model.rowCount) return
        model.rows[modelIdx].revealed = !model.rows[modelIdx].revealed
        model.fireTableRowsUpdated(modelIdx, modelIdx)
        actionToolbar.updateActionsAsync()
    }

    /**
     * EDT-only. Copies the selected row's value to the system clipboard.
     *
     * Value goes to the clipboard only — never to a log, notification, or other
     * persisted surface (see §6 of CLAUDE.md).
     */
    internal fun copySelectedValue() {
        val row = selectedSecretRow() ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(row.value))
    }

    /**
     * EDT-only. Confirms with the user, then deletes the selected secret asynchronously.
     */
    internal fun deleteSelectedWithConfirm() {
        val row = selectedSecretRow() ?: return
        confirmAndDelete(row)
    }

    /** EDT-only. Shared confirm + async delete used by both toolbar and context menu. */
    private fun confirmAndDelete(row: SecretRow) {
        val confirm = JOptionPane.showConfirmDialog(
            this,
            DopplerBundle.message("toolwindow.delete.confirm", row.key),
            DopplerBundle.message("toolwindow.context.delete"),
            JOptionPane.OK_CANCEL_OPTION,
        )
        if (confirm == JOptionPane.OK_OPTION) deleteSecretAsync(row)
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
    internal fun showAddDialog() {
        val nameField = JTextField(20)
        val valueField = JTextField(20)
        // GridLayout(rows, cols) keeps label and field on the same row.
        val form = JPanel(java.awt.GridLayout(2, 2, 4, 4)).apply {
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
        val viewIdx = table.rowAtPoint(e.point)
        if (viewIdx < 0) return
        table.selectionModel.setSelectionInterval(viewIdx, viewIdx)
        val modelIdx = table.convertRowIndexToModel(viewIdx)
        buildContextMenu(model.rows[modelIdx]).show(e.component, e.x, e.y)
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
                actionToolbar.updateActionsAsync()
            }
        }

        menu.add(DopplerBundle.message("toolwindow.context.delete")).addActionListener {
            confirmAndDelete(capturedRow)
        }

        return menu
    }
}

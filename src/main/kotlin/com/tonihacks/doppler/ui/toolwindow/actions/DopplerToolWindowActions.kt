package com.tonihacks.doppler.ui.toolwindow.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.tonihacks.doppler.DopplerBundle
import com.tonihacks.doppler.ui.toolwindow.DopplerToolWindowPanel

/**
 * `AnAction` subclasses backing the Doppler tool window toolbar.
 *
 * Rationale for using `AnAction` over plain [javax.swing.JButton]:
 *  - Click feedback (rollover, pressed) handled by the platform.
 *  - Enabled state declarative via [AnAction.update]; the toolbar refreshes when
 *    [com.intellij.openapi.actionSystem.ActionToolbar.updateActionsImmediately] is called.
 *  - Icons sourced from [AllIcons] match IDE theme and DPI scaling automatically.
 *
 * Each action takes a [DopplerToolWindowPanel] reference and delegates to its
 * EDT-only internal methods. The panel is the single owner of mutable Swing state;
 * actions are stateless wrappers.
 *
 * ## Threading
 *
 * All [actionPerformed] / [update] calls run on the EDT (declared via
 * [getActionUpdateThread]). Long-running work is dispatched onto a background pool
 * inside the panel's own methods.
 */

private const val SETTINGS_CONFIGURABLE_ID = "com.tonihacks.doppler.settings"

internal class RefreshAction(private val panel: DopplerToolWindowPanel) : AnAction(
    DopplerBundle.messagePointer("toolwindow.action.refresh"),
    DopplerBundle.messagePointer("toolwindow.action.refresh.description"),
    AllIcons.Actions.Refresh,
) {
    override fun actionPerformed(e: AnActionEvent) {
        panel.loadSecretsAsync()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !panel.isLoading
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

internal class AddSecretAction(private val panel: DopplerToolWindowPanel) : AnAction(
    DopplerBundle.messagePointer("toolwindow.action.add"),
    DopplerBundle.messagePointer("toolwindow.action.add.description"),
    AllIcons.General.Add,
) {
    override fun actionPerformed(e: AnActionEvent) {
        panel.showAddDialog()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

internal class RevealHideAction(private val panel: DopplerToolWindowPanel) : AnAction(
    DopplerBundle.messagePointer("toolwindow.action.reveal"),
    DopplerBundle.messagePointer("toolwindow.action.reveal.description"),
    AllIcons.Actions.Show,
) {
    override fun actionPerformed(e: AnActionEvent) {
        panel.toggleRevealOnSelected()
    }

    override fun update(e: AnActionEvent) {
        val selected = panel.selectedSecretRow()
        e.presentation.isEnabled = selected != null
        // Icon + text flip based on the selected row's reveal state.
        if (selected != null && selected.revealed) {
            e.presentation.icon = AllIcons.Actions.Cancel
            e.presentation.text = DopplerBundle.message("toolwindow.action.hide")
            e.presentation.description = DopplerBundle.message("toolwindow.action.hide.description")
        } else {
            e.presentation.icon = AllIcons.Actions.Show
            e.presentation.text = DopplerBundle.message("toolwindow.action.reveal")
            e.presentation.description = DopplerBundle.message("toolwindow.action.reveal.description")
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

internal class CopyValueAction(private val panel: DopplerToolWindowPanel) : AnAction(
    DopplerBundle.messagePointer("toolwindow.action.copy.value"),
    DopplerBundle.messagePointer("toolwindow.action.copy.value.description"),
    AllIcons.Actions.Copy,
) {
    override fun actionPerformed(e: AnActionEvent) {
        panel.copySelectedValue()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel.selectedSecretRow() != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

internal class DeleteSecretAction(private val panel: DopplerToolWindowPanel) : AnAction(
    DopplerBundle.messagePointer("toolwindow.action.delete"),
    DopplerBundle.messagePointer("toolwindow.action.delete.description"),
    AllIcons.General.Remove,
) {
    override fun actionPerformed(e: AnActionEvent) {
        panel.deleteSelectedWithConfirm()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel.selectedSecretRow() != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

internal class OpenSettingsAction(private val project: Project) : AnAction(
    DopplerBundle.messagePointer("toolwindow.action.settings"),
    DopplerBundle.messagePointer("toolwindow.action.settings.description"),
    AllIcons.General.Settings,
) {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, SETTINGS_CONFIGURABLE_ID)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

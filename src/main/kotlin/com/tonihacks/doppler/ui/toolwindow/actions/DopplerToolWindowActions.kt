package com.tonihacks.doppler.ui.toolwindow.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.tonihacks.doppler.DopplerBundle
import com.tonihacks.doppler.settings.DopplerSettingsConfigurable
import com.tonihacks.doppler.ui.toolwindow.DopplerToolWindowPanel

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

    override fun update(e: AnActionEvent) {
        // Disabled mid-fetch so an Add submit can't race the tail of an in-progress reload.
        e.presentation.isEnabled = !panel.isLoading
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
        ShowSettingsUtil.getInstance().showSettingsDialog(project, DopplerSettingsConfigurable::class.java)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

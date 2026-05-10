package com.tonihacks.doppler.ui.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class DopplerToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DopplerToolWindowPanel(project)
        val content = toolWindow.contentManager.factory.createContent(
            panel,
            /* displayName = */ null,
            /* isLockable = */ false,
        )
        toolWindow.contentManager.addContent(content)
        panel.loadSecretsAsync()
    }
}

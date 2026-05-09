package com.tonihacks.doppler.ui.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * Entry point for the **Doppler** tool window.
 *
 * Registered in `plugin.xml` as:
 * ```xml
 * <toolWindow id="Doppler" anchor="right"
 *     factoryClass="com.tonihacks.doppler.ui.toolwindow.DopplerToolWindowFactory"
 *     icon="/META-INF/pluginIcon.svg"/>
 * ```
 *
 * Creates one [DopplerToolWindowPanel] per project and immediately kicks off an
 * async fetch so the table is populated when the user first opens the window.
 */
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

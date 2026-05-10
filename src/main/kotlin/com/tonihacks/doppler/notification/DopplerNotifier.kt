package com.tonihacks.doppler.notification

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

/**
 * Single funnel for every user-facing message. Group `Doppler` is registered in `plugin.xml`.
 * Thread-safe — `Notification.notify` posts to the EDT internally.
 *
 * Messages must contain only secret *keys*, slugs, or CLI stderr — never values.
 */
object DopplerNotifier {

    private const val GROUP_ID = "Doppler"

    fun notifyError(project: Project, message: String) =
        notify(project, message, NotificationType.ERROR)

    fun notifyWarning(project: Project, message: String) =
        notify(project, message, NotificationType.WARNING)

    fun notifyInfo(project: Project, message: String) =
        notify(project, message, NotificationType.INFORMATION)

    private fun notify(project: Project, message: String, type: NotificationType) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
        if (group == null) {
            thisLogger().warn("NotificationGroup '$GROUP_ID' is not registered; dropping notification of type=$type")
            return
        }
        group.createNotification(message, type).notify(project)
    }
}

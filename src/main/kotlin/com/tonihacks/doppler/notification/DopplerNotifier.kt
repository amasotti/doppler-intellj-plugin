package com.tonihacks.doppler.notification

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

/**
 * Single funnel for every user-facing message in the plugin.
 *
 * Per spec §8.3: one `NotificationGroup` (`Doppler`), declared in `plugin.xml`.
 * Callers must never construct `Notification` instances directly or use `Notifications.Bus`.
 *
 * Thread-safe: may be called from any thread. `Notification.notify(project)` posts to the
 * EDT internally, so background callers (e.g. CLI failure paths) need no extra wrapping.
 *
 * Messages must never contain Doppler secret values — only secret *keys*, project / config
 * slugs, and CLI stderr (which itself must not contain values per `DopplerCliClient` contract).
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

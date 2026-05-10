package com.tonihacks.doppler.injection.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-project, per-session memo of "which run configs already got the shadow warning?".
 * Session = project lifetime; closing and reopening the project re-arms the warning.
 *
 * Atomic [markReportedIfNew] so concurrent injectors don't double-notify.
 */
@Service(Service.Level.PROJECT)
class OverrideTracker {

    private val reported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun markReportedIfNew(configName: String): Boolean = reported.add(configName)

    companion object {
        fun getInstance(project: Project): OverrideTracker = project.service()
    }
}

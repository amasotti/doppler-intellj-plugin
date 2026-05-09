package com.tonihacks.doppler.injection.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-scoped session memory of "for which run configs have we already shown the
 * 'N Doppler-managed env vars are shadowed by local values' warning?".
 *
 * **Why this exists (spec §5.3):** every launch of a config whose local env shadows
 * one or more Doppler-managed keys would otherwise re-trigger the warning. One
 * warning per session per config is enough — the user got the message the first time.
 *
 * **Session = project lifetime.** Closing and re-opening the project re-creates the
 * tracker, so the warning fires once per IDE session per config. That matches user
 * intuition for "fresh start".
 *
 * **Keyed by configName only.** A Gradle config and a Java config sharing the same
 * display name suppress each other's warning after the first fires. Acceptable v1
 * trade-off; a richer key (family + name) is a Phase 7+ change if reported.
 *
 * **Single atomic API ([markReportedIfNew]).** Run-config extensions can fire from
 * arbitrary threads (the Run dialog, the Run executor, background CLI fetches). A
 * naive `if (!hasReported(name)) { notify(); markReported(name) }` is racy: two
 * threads can both observe `false` and both notify. The atomic
 * `Set.add(): Boolean` returns `true` only for the thread that actually inserted, so
 * the caller pattern is `if (overridden.isNotEmpty() && tracker.markReportedIfNew(name)) { notify(...) }`.
 */
@Service(Service.Level.PROJECT)
class OverrideTracker {

    private val reported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Atomically records [configName] as "warned about" and returns whether this call
     * was the first to do so. Callers should fire the override notification iff this
     * returns `true`.
     */
    fun markReportedIfNew(configName: String): Boolean = reported.add(configName)

    companion object {
        fun getInstance(project: Project): OverrideTracker = project.service()
    }
}

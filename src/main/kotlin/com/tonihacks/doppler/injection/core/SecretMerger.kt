package com.tonihacks.doppler.injection.core

/**
 * Outcome of merging a run config's existing env map with Doppler-supplied secrets.
 *
 * @property merged the env map that should be passed to the launched process: union of
 *   `existing` and `doppler`, with **local (existing) values winning** on key collision.
 * @property shadowedKeys keys present in **both** `existing` and `doppler`, regardless
 *   of value equality. See [SecretMerger.merge] for the rationale on why this is a key intersection rather
 *   than a value-aware diff.
 */
data class MergeResult(
    val merged: Map<String, String>,
    val shadowedKeys: Set<String>,
) {
    override fun toString(): String =
        "MergeResult(merged=[REDACTED x${merged.size}], shadowedKeys=$shadowedKeys)"
}

/**
 * Pure, platform-agnostic env-map merger. Reused by every run-config family adapter
 * (Gradle, Java, JUnit, ...) so the conflict policy is encoded in **one** place.
 */
object SecretMerger {
    fun merge(existing: Map<String, String>, doppler: Map<String, String>): MergeResult {
        val shadowedKeys: Set<String> =
            if (existing.isEmpty() || doppler.isEmpty()) emptySet()
            else doppler.keys.intersect(existing.keys)

        val merged = HashMap<String, String>(existing.size + doppler.size).apply {
            putAll(doppler)
            putAll(existing) // local wins on collision — Doppler is the fallback
        }
        return MergeResult(merged.redactedView(), shadowedKeys)
    }

    // Mirrors DopplerProjectService.redactedView — a thin Map decorator that overrides
    // toString to a redacted summary. Duplicated (4 lines) rather than shared because
    // the only two call sites today are this and the service; promoting to a shared
    // helper after a third call site appears is the cheaper sequence.
    private fun Map<String, String>.redactedView(): Map<String, String> =
        object : Map<String, String> by this {
            override fun toString(): String = "[REDACTED x${this@redactedView.size}]"
        }
}

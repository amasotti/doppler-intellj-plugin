package com.tonihacks.doppler.injection.core

/**
 * Outcome of merging existing run-config env with Doppler secrets.
 *
 * @property merged union with **local values winning** on collision.
 * @property shadowedKeys keys present in both inputs (intersection, value-agnostic).
 */
data class MergeResult(
    val merged: Map<String, String>,
    val shadowedKeys: Set<String>,
) {
    override fun toString(): String =
        "MergeResult(merged=[REDACTED x${merged.size}], shadowedKeys=$shadowedKeys)"
}

object SecretMerger {
    fun merge(existing: Map<String, String>, doppler: Map<String, String>): MergeResult {
        val shadowedKeys: Set<String> =
            if (existing.isEmpty() || doppler.isEmpty()) emptySet()
            else doppler.keys.intersect(existing.keys)

        val merged = HashMap<String, String>(existing.size + doppler.size).apply {
            putAll(doppler)
            putAll(existing) // local wins; Doppler is the fallback
        }
        return MergeResult(merged.redactedView(), shadowedKeys)
    }

    private fun Map<String, String>.redactedView(): Map<String, String> =
        object : Map<String, String> by this {
            override fun toString(): String = "[REDACTED x${this@redactedView.size}]"
        }
}

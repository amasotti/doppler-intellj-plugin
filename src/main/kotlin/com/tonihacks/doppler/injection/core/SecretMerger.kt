package com.tonihacks.doppler.injection.core

/**
 * Outcome of merging a run config's existing env map with Doppler-supplied secrets.
 *
 * @property merged the env map that should be passed to the launched process: union of
 *   `existing` and `doppler`, with **local (existing) values winning** on key collision —
 *   Doppler is the fallback. The map itself is wrapped so its `toString()` redacts
 *   values (defense against a stray `log.debug("env: ${result.merged}")`); the same
 *   caveat as `DopplerProjectService.fetchSecrets()` applies — `entries`/`values`/
 *   `keys` proxy to the inner map and a determined-careless caller can still leak.
 * @property shadowedKeys keys present in **both** `existing` and `doppler`, regardless
 *   of value equality — these are Doppler-managed keys that the local run config is
 *   currently shadowing. Keys only — spec §11.7 forbids values here. See
 *   [SecretMerger.merge] for the rationale on why this is a key intersection rather
 *   than a value-aware diff.
 */
data class MergeResult(
    val merged: Map<String, String>,
    val shadowedKeys: Set<String>,
) {
    // Override the data-class auto-generated toString so a stray `log.debug("merged: $result")`
    // cannot dump secret values. Same precedent as SecretCache.Entry.toString.
    override fun toString(): String =
        "MergeResult(merged=[REDACTED x${merged.size}], shadowedKeys=$shadowedKeys)"
}

/**
 * Pure, platform-agnostic env-map merger. Reused by every run-config family adapter
 * (Gradle, Java, JUnit, ...) so the conflict policy is encoded in **one** place.
 *
 * **Conflict policy (spec §5.3):** manually-set run-config env vars win over
 * Doppler-managed values; Doppler is the fallback. Rationale: during development a
 * dev wants to experiment with a temporary override (personal sandbox, feature flag,
 * different connection string) without rotating the team's Doppler config. The
 * trade-off — a stale local override silently shadowing a rotated Doppler secret —
 * is mitigated by [MergeResult.shadowedKeys], which downstream injectors surface as
 * a once-per-session balloon warning so the dev knows the local environment is
 * diverging from what CI / staging / prod will see.
 *
 * **Shadow reporting:** [MergeResult.shadowedKeys] is the **key intersection**, not
 * "keys whose value actually changed". Keeping the merger value-blind is deliberate:
 * the downstream notification path is bound by spec §11.7 (keys, never values), and a
 * value-aware merger pulls value-comparison logic into the core for no notification
 * benefit. The cost — a benign "Doppler value is shadowed" balloon when the local
 * and Doppler values happened to match — is acceptable in v1; revisit only if reported.
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

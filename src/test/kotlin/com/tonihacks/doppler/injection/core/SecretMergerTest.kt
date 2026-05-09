package com.tonihacks.doppler.injection.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test

class SecretMergerTest {

    @Test
    fun `merge with both empty produces empty merged map and empty overrides`() {
        val result = SecretMerger.merge(existing = emptyMap<String, String>(), doppler = emptyMap<String, String>())

        assertThat(result.merged).isEmpty()
        assertThat(result.overriddenKeys).isEmpty()
    }

    @Test
    fun `merge with empty existing returns doppler entries verbatim and no overrides`() {
        val doppler = mapOf("API_KEY" to "abc", "DB_URL" to "FAKE_DB_URL")

        val result = SecretMerger.merge(existing = emptyMap<String, String>(), doppler = doppler)

        assertThat(result.merged).containsExactlyInAnyOrderEntriesOf(doppler)
        assertThat(result.overriddenKeys).isEmpty()
    }

    @Test
    fun `merge with empty doppler returns existing entries verbatim and no overrides`() {
        val existing = mapOf("USER_HOME" to "/home/me", "PATH" to "/usr/bin")

        val result = SecretMerger.merge(existing = existing, doppler = emptyMap<String, String>())

        assertThat(result.merged).containsExactlyInAnyOrderEntriesOf(existing)
        assertThat(result.overriddenKeys).isEmpty()
    }

    @Test
    fun `merge with no key overlap unions both maps and reports no overrides`() {
        val existing = mapOf("PATH" to "/usr/bin")
        val doppler = mapOf("API_KEY" to "abc")

        val result = SecretMerger.merge(existing, doppler)

        assertThat(result.merged).containsOnly(
            entry("PATH", "/usr/bin"),
            entry("API_KEY", "abc"),
        )
        assertThat(result.overriddenKeys).isEmpty()
    }

    @Test
    fun `doppler value wins on key collision and the key is reported as overridden`() {
        val existing = mapOf("API_KEY" to "FAKE_MANUAL", "PATH" to "/usr/bin")
        val doppler = mapOf("API_KEY" to "FAKE_DOPPLER")

        val result = SecretMerger.merge(existing, doppler)

        assertThat(result.merged).containsEntry("API_KEY", "FAKE_DOPPLER")
        assertThat(result.merged).containsEntry("PATH", "/usr/bin")
        assertThat(result.overriddenKeys).containsExactly("API_KEY")
    }

    @Test
    fun `key present in both with identical value is still reported as overridden — pinned design choice`() {
        // Pin: overriddenKeys is the key intersection, not "keys whose value actually changed".
        // Rationale: KISS — value-equality logic in the merge pulls value-aware code into
        // the core that is supposed to stay value-blind (keys-only is a security invariant
        // for downstream notifications per spec §11.7). If a future maintainer "tightens"
        // this to value-aware, the notification path stays correct but the merger becomes
        // value-aware code with no notification benefit.
        val same = mapOf("FOO" to "bar")

        val result = SecretMerger.merge(existing = same, doppler = same)

        assertThat(result.merged).containsExactlyInAnyOrderEntriesOf(same)
        assertThat(result.overriddenKeys).containsExactly("FOO")
    }

    @Test
    fun `partial overlap reports exactly the overlapping keys`() {
        val existing = mapOf("A" to "1", "B" to "2", "C" to "3")
        val doppler = mapOf("B" to "FAKE_B", "C" to "FAKE_C", "D" to "FAKE_D")

        val result = SecretMerger.merge(existing, doppler)

        assertThat(result.merged).containsOnly(
            entry("A", "1"),
            entry("B", "FAKE_B"),
            entry("C", "FAKE_C"),
            entry("D", "FAKE_D"),
        )
        assertThat(result.overriddenKeys).containsExactlyInAnyOrder("B", "C")
    }

    @Test
    fun `MergeResult toString redacts values to defend against stray log statements`() {
        val existing = mapOf("PATH" to "/usr/bin")
        val doppler = mapOf("API_KEY" to "supersecret-value")

        val rendered = SecretMerger.merge(existing, doppler).toString()

        assertThat(rendered).doesNotContain("supersecret-value", "/usr/bin", "API_KEY", "PATH")
        assertThat(rendered).contains("[REDACTED x2]")
    }

    @Test
    fun `merged map toString redacts values to defend against stray log statements`() {
        // Mirrors the DopplerProjectServiceTest precedent: a stray `log.debug("env: $merged")`
        // must not leak. Same caveat as that test — `entries`/`values`/`keys` still proxy.
        val existing = emptyMap<String, String>()
        val doppler = mapOf("API_KEY" to "supersecret-value")

        val rendered = SecretMerger.merge(existing, doppler).merged.toString()

        assertThat(rendered).doesNotContain("supersecret-value", "API_KEY")
        assertThat(rendered).isEqualTo("[REDACTED x1]")
    }

    @Test
    fun `mutating input maps after merge does not affect the merged result`() {
        // Snapshot guarantee: the merger reads its inputs once. A caller that mutates
        // the manual env map after the merge call must not see those mutations leak
        // into the launched process.
        val existing = mutableMapOf("A" to "1")
        val doppler = mutableMapOf("B" to "2")

        val result = SecretMerger.merge(existing, doppler)
        existing["X"] = "added-after-merge"
        doppler["Y"] = "added-after-merge"

        assertThat(result.merged).containsOnlyKeys("A", "B")
    }
}

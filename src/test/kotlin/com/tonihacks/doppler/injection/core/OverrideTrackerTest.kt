package com.tonihacks.doppler.injection.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class OverrideTrackerTest {

    @Test
    fun `markReportedIfNew returns true on first call for a configName`() {
        val tracker = OverrideTracker()
        assertThat(tracker.markReportedIfNew("Run App")).isTrue()
    }

    @Test
    fun `markReportedIfNew returns false on subsequent calls for the same configName`() {
        val tracker = OverrideTracker()
        tracker.markReportedIfNew("Run App")
        assertThat(tracker.markReportedIfNew("Run App")).isFalse()
        assertThat(tracker.markReportedIfNew("Run App")).isFalse()
    }

    @Test
    fun `different configNames are tracked independently`() {
        val tracker = OverrideTracker()
        assertThat(tracker.markReportedIfNew("Run App")).isTrue()
        assertThat(tracker.markReportedIfNew("Run Tests")).isTrue()
    }

    @Test
    fun `concurrent markReportedIfNew on the same configName lets exactly one caller win`() {
        // The whole point of the atomic API: when N threads race on the same key,
        // exactly one observes `true` (and would fire the notification); the rest see
        // `false`. A non-atomic check-then-set would let 2+ threads both fire.
        val tracker = OverrideTracker()
        val threadCount = 32
        val winners = AtomicInteger(0)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val gate = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        val threads = (1..threadCount).map {
            Thread {
                try {
                    gate.await()
                    if (tracker.markReportedIfNew("contended-config")) winners.incrementAndGet()
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    done.countDown()
                }
            }
        }
        threads.forEach { it.start() }
        gate.countDown() // release all threads simultaneously
        check(done.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "threads did not finish in 5s" }

        assertThat(errors).isEmpty()
        assertThat(winners.get()).isEqualTo(1)
    }
}

package com.example.safetymonitor.detection.events

import com.example.safetymonitor.network.HeartRateStatus
import com.example.safetymonitor.watch.WatchSummary
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HeartStatusContractTest {
    private fun sample(n: Int, bpm: Double? = 130.0, count: Int = 3) =
        WatchSummary(1L + n * 300_000L, 1L + (n + 1) * 300_000L, 5, bpm, null, null, count, 0)

    @Test fun normalBoundariesAndConfirmedDangerShareEventDecision() {
        val rule = VitalRule(EventSettings.fixedConfig)
        for ((n, bpm) in listOf(50.0, 120.0, 121.0, 121.0, 121.0, 80.0).withIndex()) {
            val s = sample(n, bpm)
            val events = rule.evaluate(s, s.timestamp)
            assertEquals(if (n == 3) 1 else 0, events.size)
            val expected = when (n) { 2 -> null; 3, 4 -> HeartRateStatus.DANGER; else -> HeartRateStatus.NORMAL }
            assertEquals(expected, rule.heartAssessment?.status)
            assertEquals(if (expected == null) null else Instant.ofEpochMilli(s.timestamp), rule.heartAssessment?.measuredAt)
        }
    }

    @Test fun unknownDuplicateStaleAndTenMinuteDataNeverRefreshAssessment() {
        val rule = VitalRule(EventSettings.fixedConfig)
        val normal = sample(0, 75.0)
        rule.evaluate(normal, normal.timestamp)
        assertNotNull(rule.heartAssessment)
        rule.evaluate(normal, normal.timestamp + 1000)
        assertNull(rule.heartAssessment)
        for (s in listOf(sample(1, null, 0), sample(2, 80.0, 2), sample(3).copy(intervalMinutes = 10))) {
            assertTrue(rule.evaluate(s, s.timestamp).isEmpty())
            assertNull(rule.heartAssessment)
        }
        val stale = sample(4)
        rule.evaluate(stale, stale.timestamp + 420_001)
        assertNull(rule.heartAssessment)
    }

    @Test fun gapDirectionChangeAndInsufficientSamplesRequireNewConfirmation() {
        val rule = VitalRule(EventSettings.fixedConfig)
        for (s in listOf(sample(0), sample(2), sample(3, 40.0), sample(4, 40.0, 2), sample(5, 40.0))) {
            assertTrue(rule.evaluate(s, s.timestamp).isEmpty())
            assertNull(rule.heartAssessment)
        }
        val confirmed = sample(6, 40.0)
        assertEquals(1, rule.evaluate(confirmed, confirmed.timestamp).size)
        assertEquals(HeartRateStatus.DANGER, rule.heartAssessment?.status)
        val gap = sample(8, 40.0)
        assertTrue(rule.evaluate(gap, gap.timestamp).isEmpty())
        assertNull(rule.heartAssessment)
    }
}

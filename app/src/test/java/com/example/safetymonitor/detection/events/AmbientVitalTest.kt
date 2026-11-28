package com.example.safetymonitor.detection.events

import com.example.safetymonitor.watch.WatchSummary
import org.junit.Assert.*
import org.junit.Test

class AmbientVitalTest {
    private fun calibrated(ambient: Double): SkinTrendRule = SkinTrendRule().also { rule ->
        repeat(6) { assertFalse(rule.evaluate(33.0, it > 0, 2.0, ambient)) }
        assertEquals(33.0, rule.baseline!!, 0.0)
    }

    @Test fun coldNormalAndHotEnvironmentsDetectBothDirections() {
        for (ambient in listOf(15.0, 25.0, 30.0)) for (skin in listOf(31.0, 35.0)) {
            val rule = calibrated(ambient)
            assertFalse(rule.evaluate(skin, true, 2.0, ambient))
            assertTrue(rule.evaluate(skin, true, 2.0, ambient))
            assertFalse(rule.evaluate(skin, true, 2.0, ambient))
        }
    }

    @Test fun environmentChangeDoesNotUseOldBaselineOrAbnormalRun() {
        val rule = calibrated(25.0)
        assertFalse(rule.evaluate(35.0, true, 2.0, 25.0))
        assertFalse(rule.evaluate(35.0, true, 2.0, 32.0))
        assertNull(rule.baseline)
        assertTrue(rule.status.contains("고온 환경"))
        repeat(5) { assertFalse(rule.evaluate(35.0, true, 2.0, 32.0)) }
        assertEquals(35.0, rule.baseline!!, 0.0)
        assertFalse(rule.evaluate(37.0, true, 2.0, 32.0))
        assertTrue(rule.evaluate(37.0, true, 2.0, 32.0))
    }

    @Test fun largeAmbientChangeWithinSameEnvironmentRecalibrates() {
        val rule = calibrated(22.0)
        assertFalse(rule.evaluate(35.0, true, 2.0, 25.0))
        assertNull(rule.baseline)
    }

    @Test fun unstableAmbientAndMissingAmbientCannotBuildBaseline() {
        val rule = SkinTrendRule()
        repeat(12) { assertFalse(rule.evaluate(33.0, true, 2.0, if (it % 2 == 0) 23.0 else 26.0)) }
        assertNull(rule.baseline)
        assertFalse(rule.evaluate(33.0, true, 2.0, null))
        repeat(5) { assertFalse(rule.evaluate(33.0, true, 2.0, 25.0)) }
        assertNull(rule.baseline)
        assertFalse(rule.evaluate(33.0, true, 2.0, 25.0))
        assertEquals(33.0, rule.baseline!!, 0.0)
    }

    @Test fun missingAmbientBreaksRunButDoesNotRearmReportedEpisode() {
        val rule = calibrated(25.0)
        assertFalse(rule.evaluate(31.0, true, 2.0, 25.0))
        assertFalse(rule.evaluate(31.0, true, 2.0, Double.NaN))
        assertFalse(rule.evaluate(31.0, true, 2.0, 25.0))
        assertTrue(rule.evaluate(31.0, true, 2.0, 25.0))
        assertFalse(rule.evaluate(33.0, true, 2.0, null))
        repeat(2) { assertFalse(rule.evaluate(31.0, true, 2.0, 25.0)) }
        assertFalse(rule.evaluate(33.0, true, 2.0, 25.0))
        assertFalse(rule.evaluate(31.0, true, 2.0, 25.0))
        assertTrue(rule.evaluate(31.0, true, 2.0, 25.0))
    }

    @Test fun oppositeDirectionsAndGapsCannotConfirmAnomaly() {
        val rule = calibrated(10.0)
        assertFalse(rule.evaluate(31.0, true, 2.0, 10.0))
        assertFalse(rule.evaluate(35.0, true, 2.0, 10.0))
        assertFalse(rule.evaluate(31.0, true, 2.0, 10.0))
        assertFalse(rule.evaluate(31.0, false, 2.0, 10.0))
        assertTrue(rule.evaluate(31.0, true, 2.0, 10.0))
    }

    @Test fun productionRuleUsesAmbientAndLeavesHeartDetectionIndependent() {
        val rule = VitalRule(EventSettings.fixedConfig)
        for (i in 0..7) {
            val s = WatchSummary(1L + i * 300000L, 1L + (i + 1) * 300000L, 5,
                80.0, if (i < 6) 33.0 else 31.0, 10.0, 10, 10)
            val events = rule.evaluate(s, s.timestamp)
            assertEquals(if (i == 7) listOf("체온 이상 SOS") else emptyList<String>(), events.map { it.sender })
            assertTrue(rule.evaluate(s, s.timestamp).isEmpty())
        }
        val heart = VitalRule(EventSettings.fixedConfig)
        for (i in 0..1) {
            val s = WatchSummary(1L + i * 300000L, 1L + (i + 1) * 300000L, 5,
                130.0, 33.0, null, 10, 10)
            assertEquals(if (i == 1) listOf("심박수 이상 SOS") else emptyList<String>(),
                heart.evaluate(s, s.timestamp).map { it.sender })
        }
    }
}

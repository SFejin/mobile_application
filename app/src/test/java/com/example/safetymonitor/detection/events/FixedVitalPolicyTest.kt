package com.example.safetymonitor.detection.events

import com.example.safetymonitor.watch.WatchSummary
import org.junit.Assert.*
import org.junit.Test

class FixedVitalPolicyTest {
    private fun sample(n: Int, hr: Double, skin: Double? = 33.9) = WatchSummary(1L+n*300000L, 1L+(n+1)*300000L,
        5, hr, skin, 60.0, 10, if (skin != null) 10 else 0)
    @Test fun heartBoundariesBelowThresholdSkinAndAmbientTemperatureDoNotAlert() {
        val rule = VitalRule(EventSettings.state.value)
        for (n in 0..3) {
            val s = sample(n, if(n%2==0) 50.0 else 120.0)
            assertTrue(rule.evaluate(s,s.timestamp).isEmpty())
        }
    }

    @Test fun skinThresholdIsInclusiveAndRequiresDistinctConsecutiveWindows() {
        val rule = VitalRule(EventConfig(skin = VitalLimits(10.0, 34.0)))
        val first = sample(0, 80.0, 34.0)
        assertTrue(rule.evaluate(first, first.timestamp).isEmpty())
        assertTrue(rule.evaluate(first, first.timestamp).isEmpty())
        for ((index, skin) in listOf(null, 34.0, 34.0, 35.0, 33.9, 34.0, 34.0).withIndex()) {
            val next = sample(index + 1, 80.0, skin)
            val expected = if (index == 2 || index == 6) listOf("체온 이상 SOS") else emptyList()
            assertEquals(expected, rule.evaluate(next, next.timestamp).map { it.sender })
        }
    }
    @Test fun eachSideNeedsTwoWindowsThenOnlyHeartEventIsSent() {
        for (hr in listOf(49.0,121.0)) {
            val rule = VitalRule(EventSettings.state.value)
            val a=sample(0,hr); val b=sample(1,hr); val c=sample(2,hr)
            assertTrue(rule.evaluate(a,a.timestamp).isEmpty())
            assertNotNull(rule.heartCheck)
            assertTrue(rule.evaluate(a,a.timestamp).isEmpty())
            assertEquals(listOf("심박수 이상 SOS"),rule.evaluate(b,b.timestamp).map{it.sender})
            assertNull(rule.heartCheck)
            assertTrue(rule.evaluate(c,c.timestamp).isEmpty())
        }
    }

    @Test fun manualHelpPreventsDuplicateReportUntilRecovery() {
        val rule = VitalRule(EventSettings.fixedConfig)
        val first = sample(0, 48.0)
        assertTrue(rule.evaluate(first, first.timestamp).isEmpty())
        assertNotNull(rule.heartCheck)
        rule.markHeartReported()
        for ((index, bpm) in listOf(48.0, 48.0, 75.0, 48.0, 48.0).withIndex()) {
            val next = sample(index + 1, bpm)
            assertEquals(if (index == 4) 1 else 0, rule.evaluate(next, next.timestamp).size)
        }
    }
}

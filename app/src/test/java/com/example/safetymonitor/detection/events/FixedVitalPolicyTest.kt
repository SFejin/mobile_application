package com.example.safetymonitor.detection.events

import com.example.safetymonitor.watch.WatchSummary
import org.junit.Assert.*
import org.junit.Test

class FixedVitalPolicyTest {
    private fun sample(n: Int, hr: Double) = WatchSummary(1L+n*300000L, 1L+(n+1)*300000L,
        5, hr, 49.0, 60.0, 10, 10)
    @Test fun fixedBoundaryValuesAndTemperaturesDoNotAlert() {
        val rule = VitalRule(EventSettings.state.value)
        for (n in 0..3) {
            val s = sample(n, if(n%2==0) 50.0 else 119.0)
            assertTrue(rule.evaluate(s,s.timestamp).isEmpty())
        }
        assertNull(EventSettings.state.value.skin)
    }
    @Test fun eachSideNeedsTwoWindowsThenOnlyHeartEventIsSent() {
        for (hr in listOf(49.0,120.0)) {
            val rule = VitalRule(EventSettings.state.value)
            val a=sample(0,hr); val b=sample(1,hr); val c=sample(2,hr)
            assertTrue(rule.evaluate(a,a.timestamp).isEmpty())
            assertNotNull(rule.heartCheck)
            assertTrue(rule.evaluate(a,a.timestamp).isEmpty())
            assertEquals(listOf("ANDROID_HEART_RATE"),rule.evaluate(b,b.timestamp).map{it.sender})
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

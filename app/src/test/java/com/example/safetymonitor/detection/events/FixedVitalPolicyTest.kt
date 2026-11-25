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
            val s = sample(n, if(n%2==0) 50.0 else 120.0)
            assertTrue(rule.evaluate(s,s.timestamp).isEmpty())
        }
        assertNull(EventSettings.state.value.skin)
    }
    @Test fun eachSideNeedsTwoWindowsThenOnlyHeartEventIsSent() {
        for (hr in listOf(49.0,121.0)) {
            val rule = VitalRule(EventSettings.state.value)
            val a=sample(0,hr); val b=sample(1,hr); val c=sample(2,hr)
            assertTrue(rule.evaluate(a,a.timestamp).isEmpty())
            assertTrue(rule.evaluate(a,a.timestamp).isEmpty())
            assertEquals(listOf("ANDROID_HEART_RATE"),rule.evaluate(b,b.timestamp).map{it.sender})
            assertTrue(rule.evaluate(c,c.timestamp).isEmpty())
        }
    }
}

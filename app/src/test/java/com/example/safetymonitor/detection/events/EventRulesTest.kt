package com.example.safetymonitor.detection.events

import com.example.safetymonitor.detection.beacon.*
import com.example.safetymonitor.watch.WatchSummary
import org.junit.Assert.*
import org.junit.Test

class EventRulesTest {
    private fun beacon(t: Long, d: Double = 1.0, id: Int = 7, conflict: Boolean = false) = BeaconSnapshot(
        status = BeaconStatus.ACTIVE, observations = listOf(BeaconObservation(BeaconConfig(id), -50.0, d, t, conflict)))
    private fun hr(start: Long, value: Double?, count: Int = 10, interval: Int = 5) =
        WatchSummary(start, start + interval * 60_000, interval, value, null, null, count, 0)

    @Test fun proximityNeedsFreshDistinctSamplesAndThreeSeconds() {
        val rule = DangerZoneRule(setOf(7))
        assertTrue(rule.evaluate(beacon(1000), 1000).isEmpty())
        assertTrue(rule.evaluate(beacon(1000), 4000).isEmpty())
        assertTrue(rule.evaluate(beacon(2000), 4000).isEmpty())
        assertEquals(1, rule.evaluate(beacon(4000), 4000).size)
        assertTrue(rule.evaluate(beacon(8000), 8000).isEmpty())
    }
    @Test fun noDangerIdsNoEventsAndOtherAnchorsIgnored() {
        for (t in listOf(1000L, 2000, 4000)) {
            assertTrue(DangerZoneRule(emptySet()).evaluate(beacon(t), t).isEmpty())
            assertTrue(DangerZoneRule(setOf(8)).evaluate(beacon(t), t).isEmpty())
        }
    }
    @Test fun missingConflictedAndStaleSignalsBreakConfirmation() {
        for (invalid in listOf(beacon(2000, conflict = true), beacon(2000, Double.NaN), beacon(2000).copy(role = BeaconRole.TRANSMITTER),
            beacon(2000).copy(status = BeaconStatus.STOPPED), beacon(0))) {
            val rule = DangerZoneRule(setOf(7))
            rule.evaluate(beacon(1000),1000)
            rule.evaluate(invalid,6000)
            assertTrue(rule.evaluate(beacon(7000),7000).isEmpty())
            assertTrue(rule.evaluate(beacon(8000),8000).isEmpty())
            assertEquals(1,rule.evaluate(beacon(10000),10000).size)
        }
    }
    @Test fun lostSignalDoesNotRearmAndLeavingNeedsEvidenceAndCooldown() {
        val rule = DangerZoneRule(setOf(7))
        for(t in listOf(1000L,2000,4000)) rule.evaluate(beacon(t),t)
        rule.evaluate(BeaconSnapshot(),20000)
        for(t in listOf(21000L,22000,24000)) assertTrue(rule.evaluate(beacon(t),t).isEmpty())
        for(t in listOf(25000L,26000,28000)) assertTrue(rule.evaluate(beacon(t,3.0),t).isEmpty())
        for(t in listOf(29000L,30000,32000)) assertTrue(rule.evaluate(beacon(t),t).isEmpty())
        assertTrue(rule.evaluate(beacon(65000),65000).isEmpty())
        assertTrue(rule.evaluate(beacon(66000),66000).isEmpty())
        assertEquals(1,rule.evaluate(beacon(68000),68000).size)
    }
    @Test fun twoConsecutiveAveragesTriggerOnceAndNormalRearms() {
        val rule=VitalRule(EventConfig())
        val first=hr(1000,120.0)
        assertNull(rule.evaluate(first,first.timestamp))
        assertNull(rule.evaluate(first,first.timestamp+1000))
        val second=hr(first.timestamp,120.0)
        assertNotNull(rule.evaluate(second,second.timestamp))
        val third=hr(second.timestamp,130.0)
        assertNull(rule.evaluate(third,third.timestamp))
        val normal=hr(third.timestamp,80.0)
        assertNull(rule.evaluate(normal,normal.timestamp))
        val low=hr(normal.timestamp,50.0)
        assertNull(rule.evaluate(low,low.timestamp))
        val low2=hr(low.timestamp,49.0)
        assertNotNull(rule.evaluate(low2,low2.timestamp))
    }
    @Test fun oppositeDirectionGapOverlapAndLowSampleCountDoNotConfirm() {
        for(next in listOf(hr(301000,49.0),hr(500000,130.0),hr(200000,130.0),hr(301000,130.0,2),hr(301000,null,0))) {
            val rule=VitalRule(EventConfig())
            val first=hr(1000,130.0)
            rule.evaluate(first,first.timestamp)
            assertNull(rule.evaluate(next,next.timestamp))
        }
    }
    @Test fun staleFutureAndDisabledHeartProduceNoEvent() {
        val first=hr(1000,130.0); val next=hr(first.timestamp,130.0)
        for(config in listOf(EventConfig(), EventConfig(heartEnabled=false))) {
            val rule=VitalRule(config)
            rule.evaluate(first,first.timestamp)
            assertNull(rule.evaluate(next,next.timestamp+420001))
        }
        val rule=VitalRule(EventConfig())
        assertNull(rule.evaluate(next,1000))
    }
    @Test fun configurationRejectsInvalidIdsAndThresholds() {
        assertThrows(IllegalArgumentException::class.java) { EventConfig(setOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { EventConfig(lowHeartRate=99,highHeartRate=100) }
    }
}

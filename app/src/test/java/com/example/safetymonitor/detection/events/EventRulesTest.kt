package com.example.safetymonitor.detection.events

import com.example.safetymonitor.detection.beacon.*
import com.example.safetymonitor.watch.WatchSummary
import org.junit.Assert.*
import org.junit.Test

class EventRulesTest {
    private fun summary(n: Int, hr: Double? = 140.0, skin: Double? = 40.0) =
        WatchSummary(1L + n * 300_000L, 1L + (n + 1) * 300_000L, 5, hr, skin, null,
            if (hr == null) 0 else 10, if (skin == null) 0 else 10)
    private fun evaluate(rule: VitalRule, n: Int, hr: Double? = 140.0, skin: Double? = 40.0): List<DetectedEvent> {
        val s = summary(n, hr, skin)
        return rule.evaluate(s, s.timestamp)
    }
    @Test fun distinctWindowsAndRecoveryControlBothVitalAlerts() {
        val rule = VitalRule(EventConfig(VitalLimits(50.0, 120.0), VitalLimits(20.0, 38.0)))
        assertTrue(evaluate(rule, 0).isEmpty())
        assertTrue(evaluate(rule, 0).isEmpty())
        assertEquals(setOf("ANDROID_HEART_RATE", "ANDROID_SKIN_TEMPERATURE"), evaluate(rule, 1).map { it.sender }.toSet())
        assertTrue(evaluate(rule, 2).isEmpty())
        assertTrue(evaluate(rule, 3, null, null).isEmpty())
        assertTrue(evaluate(rule, 4).isEmpty())
        assertTrue(evaluate(rule, 5).isEmpty())
        assertTrue(evaluate(rule, 6, 80.0, 30.0).isEmpty())
        assertTrue(evaluate(rule, 7).isEmpty())
        assertEquals(2, evaluate(rule, 8).size)
    }
    @Test fun staleMissingGappedAndUnconfiguredReadingsDoNotTrigger() {
        val rule = VitalRule(EventConfig(heart = VitalLimits(50.0, 120.0)))
        assertTrue(evaluate(rule, 0).isEmpty())
        assertTrue(evaluate(rule, 2).isEmpty())
        assertTrue(evaluate(rule, 3, null).isEmpty())
        assertTrue(evaluate(rule, 4).isEmpty())
        assertTrue(rule.evaluate(summary(5), summary(5).timestamp + 500_000).isEmpty())
        assertTrue(evaluate(rule, 6).isEmpty())
        assertEquals(1, evaluate(rule, 7).size)
        val disabled = VitalRule(EventConfig())
        assertTrue(evaluate(disabled, 0).isEmpty()); assertTrue(evaluate(disabled, 1).isEmpty())
    }
    private fun beacon(at: Long, distance: Double = 1.0, danger: Boolean = true, conflict: Boolean = false) =
        BeaconSnapshot(status = BeaconStatus.ACTIVE, observations = listOf(
            BeaconObservation(BeaconConfig(danger = danger), -55.0, distance, at, conflict)))
    @Test fun dangerNeedsFreshSustainedApproachAndObservedExitToRearm() {
        val rule = DangerZoneRule()
        for (at in listOf(1000L, 2000L, 3000L)) assertTrue(rule.evaluate(beacon(at), at).isEmpty())
        assertEquals(1, rule.evaluate(beacon(4000), 4000).size)
        assertTrue(rule.evaluate(beacon(4000), 4500).isEmpty())
        assertTrue(rule.evaluate(BeaconSnapshot(), 5000).isEmpty())
        for (at in listOf(6000L, 7000L, 8000L, 9000L)) assertTrue(rule.evaluate(beacon(at), at).isEmpty())
        for (at in listOf(10000L, 11000L, 12000L, 13000L)) assertTrue(rule.evaluate(beacon(at, 4.0), at).isEmpty())
        for (at in listOf(14000L, 15000L, 16000L)) assertTrue(rule.evaluate(beacon(at), at).isEmpty())
        assertEquals(1, rule.evaluate(beacon(17000), 17000).size)
    }
    @Test fun normalConflictedStaleOrTransmittingBeaconsNeverAlert() {
        for (kind in 0..3) {
            val rule = DangerZoneRule()
            for (at in 1000L..10000L step 1000L) {
                val s = when (kind) {
                    0 -> beacon(at, danger = false)
                    1 -> beacon(at, conflict = true)
                    2 -> beacon(at - 6000)
                    else -> beacon(at).copy(role = BeaconRole.TRANSMITTER)
                }
                assertTrue(rule.evaluate(s, at).isEmpty())
            }
        }
    }
    @Test fun dangerWireKeepsEightBytesAndLegacyNormalMeaning() {
        val config = BeaconConfig(id = 42, danger = true)
        val bytes = BeaconProtocol.encode(config)
        assertEquals(8, bytes.size); assertEquals(2, bytes[0].toInt())
        assertEquals(config, BeaconProtocol.decode(bytes))
        bytes[0] = 1
        assertEquals(config.copy(danger = false), BeaconProtocol.decode(bytes))
    }
}

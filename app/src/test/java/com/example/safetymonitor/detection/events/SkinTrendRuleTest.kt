package com.example.safetymonitor.detection.events
import org.junit.Assert.*
import org.junit.Test
class SkinTrendRuleTest {
    @Test fun stableBaselineBoundaryMissingAndRecovery() {
        val rule = SkinTrendRule()
        repeat(6) { assertFalse(rule.evaluate(33.0, it > 0, 2.0, 25.0)) }
        assertEquals(33.0, rule.baseline!!, 0.0)
        assertFalse(rule.evaluate(35.0, true, 2.0, 25.0))
        assertFalse(rule.evaluate(null, true, 2.0, 25.0))
        assertFalse(rule.evaluate(35.0, true, 2.0, 25.0))
        assertTrue(rule.evaluate(35.0, true, 2.0, 25.0))
        assertFalse(rule.evaluate(36.0, true, 2.0, 25.0))
        assertFalse(rule.evaluate(34.8, true, 2.0, 25.0))
        repeat(2) { assertFalse(rule.evaluate(35.0, true, 2.0, 25.0)) }
        assertFalse(rule.evaluate(33.0, true, 2.0, 25.0))
        assertFalse(rule.evaluate(35.0, false, 2.0, 25.0))
        assertTrue(rule.evaluate(35.0, true, 2.0, 25.0))
    }
    @Test fun unstableOrDisconnectedCalibrationDoesNotArm() {
        val rule = SkinTrendRule()
        repeat(12) { assertFalse(rule.evaluate(if (it % 2 == 0) 31.0 else 35.0, true, 2.0, 25.0)) }
        assertNull(rule.baseline)
        repeat(12) { assertFalse(rule.evaluate(33.0, false, 2.0, 25.0)) }
        assertNull(rule.baseline)
    }
    @Test fun isolatedTestsProduceDistinctTestLabels() {
        assertEquals("심박수 이상 SOS · 테스트", vitalTestEvent(false, 10_000_000).sender)
        assertEquals("체온 이상 SOS · 테스트", vitalTestEvent(true, 10_000_000).sender)
    }
}

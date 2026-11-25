package com.example.safetymonitor.detection.events

import com.example.safetymonitor.watch.WatchSummary
import org.junit.Assert.*
import org.junit.Test

class SkinProfilesTest {
    @Test fun profilesRemainIndependentAndMissingEnvironmentDisablesOnlySkin() {
        val normal = VitalLimits(20.0, 35.0)
        val cold = VitalLimits(15.0, 30.0)
        val heart = VitalLimits(50.0, 120.0)
        val profiles = SkinProfiles().updated(SkinEnvironment.NORMAL, normal).updated(SkinEnvironment.COLD, cold)
        assertEquals(normal, profiles.config(heart, SkinEnvironment.NORMAL).skin)
        assertEquals(cold, profiles.config(heart, SkinEnvironment.COLD).skin)
        assertNull(profiles.config(heart, SkinEnvironment.HOT).skin)
        assertEquals(heart, profiles.config(heart, SkinEnvironment.HOT).heart)
        val removed = profiles.updated(SkinEnvironment.COLD, null)
        assertEquals(normal, removed.config(null, SkinEnvironment.NORMAL).skin)
        assertNull(removed.config(null, SkinEnvironment.COLD).skin)
    }
    @Test fun sameReadingUsesSelectedEnvironmentAndEnvironmentChangeIsObservable() {
        val limits = SkinProfiles().updated(SkinEnvironment.NORMAL, VitalLimits(20.0, 35.0))
            .updated(SkinEnvironment.HOT, VitalLimits(20.0, 40.0))
        val normal = VitalRule(limits.config(null, SkinEnvironment.NORMAL))
        val hot = VitalRule(limits.config(null, SkinEnvironment.HOT))
        fun sample(n: Int) = WatchSummary(1L + n * 300000, 300001L + n * 300000, 5, null, 37.0, null, 0, 5)
        val first = sample(0); val second = sample(1)
        assertTrue(normal.evaluate(first, first.timestamp).isEmpty())
        assertEquals(1, normal.evaluate(second, second.timestamp).size)
        assertTrue(hot.evaluate(first, first.timestamp).isEmpty())
        assertTrue(hot.evaluate(second, second.timestamp).isEmpty())
        // WorkerEventMonitor compares EventConfig to restart consecutive-window tracking.
        assertNotEquals(EventConfig(skinEnvironment = SkinEnvironment.NORMAL), EventConfig(skinEnvironment = SkinEnvironment.COLD))
    }
}

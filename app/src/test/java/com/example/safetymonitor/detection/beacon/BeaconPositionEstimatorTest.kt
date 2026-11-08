package com.example.safetymonitor.detection.beacon

import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconPositionEstimatorTest {

    @Test
    fun solvePositionRecoversExactOneOneAndDoesNotClampOutsideConvexHull() {
        val anchor1 = BeaconConfig(id = 1, xCm = 0, yCm = 0, referenceRssi = -59)
        val anchor2 = BeaconConfig(id = 2, xCm = 200, yCm = 0, referenceRssi = -59)
        val anchor3 = BeaconConfig(id = 3, xCm = 0, yCm = 200, referenceRssi = -59)

        val d = sqrt(2.0)
        val result = solvePosition(
            listOf(
                BeaconRange(anchor1, d),
                BeaconRange(anchor2, d),
                BeaconRange(anchor3, d)
            )
        )

        assertTrue("Expected Available position, got $result", result is BeaconPosition.Available)
        val available = result as BeaconPosition.Available
        assertEquals(1.0, available.xMeters, 1e-6)
        assertEquals(1.0, available.yMeters, 1e-6)
        assertEquals(3, available.anchorCount)

        // Target outside anchor convex hull at (-1.5m, 3.0m) with 4 anchors
        val anchor4 = BeaconConfig(id = 4, xCm = 200, yCm = 200, referenceRssi = -59)
        val targetX = -1.5
        val targetY = 3.0
        val outsideRanges = listOf(anchor1, anchor2, anchor3, anchor4).map { cfg ->
            BeaconRange(
                config = cfg,
                distanceMeters = hypot(targetX - (cfg.xCm / 100.0), targetY - (cfg.yCm / 100.0))
            )
        }
        val outsideResult = solvePosition(outsideRanges)
        assertTrue(outsideResult is BeaconPosition.Available)
        val outsideAvailable = outsideResult as BeaconPosition.Available
        assertEquals(targetX, outsideAvailable.xMeters, 1e-6)
        assertEquals(targetY, outsideAvailable.yMeters, 1e-6)
        assertEquals(4, outsideAvailable.anchorCount)
    }

    @Test
    fun solvePositionRejectsMissingCollinearAndNearlyCollinearGeometry() {
        val a1 = BeaconConfig(id = 1, xCm = 0, yCm = 0, referenceRssi = -59)
        val a2 = BeaconConfig(id = 2, xCm = 200, yCm = 0, referenceRssi = -59)

        // Missing anchors (< 3)
        val emptyPos = solvePosition(emptyList())
        assertTrue(emptyPos is BeaconPosition.Unavailable)

        val twoAnchorsPos = solvePosition(
            listOf(
                BeaconRange(a1, 1.0),
                BeaconRange(a2, 1.0)
            )
        )
        assertTrue(twoAnchorsPos is BeaconPosition.Unavailable)

        // Exactly collinear along X axis: (0,0), (200,0), (400,0) cm
        val collinearX = solvePosition(
            listOf(
                BeaconRange(BeaconConfig(id = 1, xCm = 0, yCm = 0), 2.0),
                BeaconRange(BeaconConfig(id = 2, xCm = 200, yCm = 0), 1.0),
                BeaconRange(BeaconConfig(id = 3, xCm = 400, yCm = 0), 2.0)
            )
        )
        assertTrue(collinearX is BeaconPosition.Unavailable)

        // Collinear along diagonal: (0,0), (150,150), (300,300) cm
        val collinearDiag = solvePosition(
            listOf(
                BeaconRange(BeaconConfig(id = 1, xCm = 0, yCm = 0), 2.0),
                BeaconRange(BeaconConfig(id = 2, xCm = 150, yCm = 150), 1.5),
                BeaconRange(BeaconConfig(id = 3, xCm = 300, yCm = 300), 2.0)
            )
        )
        assertTrue(collinearDiag is BeaconPosition.Unavailable)

        // Nearly collinear: 1 cm lateral offset across a 400 cm baseline
        val nearlyCollinear = solvePosition(
            listOf(
                BeaconRange(BeaconConfig(id = 1, xCm = 0, yCm = 0), 2.0),
                BeaconRange(BeaconConfig(id = 2, xCm = 200, yCm = 0), 1.0),
                BeaconRange(BeaconConfig(id = 3, xCm = 400, yCm = 1), 2.0)
            )
        )
        assertTrue(nearlyCollinear is BeaconPosition.Unavailable)

        // Coincident coordinates
        val coincident = solvePosition(
            listOf(
                BeaconRange(BeaconConfig(id = 1, xCm = 100, yCm = 100), 1.0),
                BeaconRange(BeaconConfig(id = 2, xCm = 100, yCm = 100), 1.0),
                BeaconRange(BeaconConfig(id = 3, xCm = 100, yCm = 100), 1.0)
            )
        )
        assertTrue(coincident is BeaconPosition.Unavailable)
    }

    @Test
    fun rollingMedianCapsAtFiveSamplesAndCalculatesLogDistance() {
        val estimator = BeaconPositionEstimator()
        val beacon = BeaconConfig(id = 1, xCm = 0, yCm = 0, referenceRssi = -59)

        // Even count (2 samples: -59, -79 -> median -69.0 -> distance 10^(( -59 - -69 )/20) = sqrt(10))
        estimator.observe(beacon, rssi = -59, observedAtElapsedMillis = 100L)
        estimator.observe(beacon, rssi = -79, observedAtElapsedMillis = 200L)
        val snap2 = estimator.snapshot(nowElapsedMillis = 200L)
        assertEquals(1, snap2.observations.size)
        assertEquals(-69.0, snap2.observations[0].rssi, 1e-6)
        assertNotNull(snap2.observations[0].distanceMeters)
        assertEquals(sqrt(10.0), snap2.observations[0].distanceMeters!!, 1e-6)

        // Fill to 5 samples: [-59, -79, -50, -85, -63] -> sorted [-85, -79, -63, -59, -50] -> median -63.0
        estimator.observe(beacon, rssi = -50, observedAtElapsedMillis = 300L)
        estimator.observe(beacon, rssi = -85, observedAtElapsedMillis = 400L)
        estimator.observe(beacon, rssi = -63, observedAtElapsedMillis = 500L)
        val snap5 = estimator.snapshot(nowElapsedMillis = 500L)
        assertEquals(-63.0, snap5.observations[0].rssi, 1e-6)

        // 6th sample (-41) and 7th sample (-45) evict oldest (-59, -79), leaving [-50, -85, -63, -41, -45]
        // Sorted: [-85, -63, -50, -45, -41] -> median -50.0
        estimator.observe(beacon, rssi = -41, observedAtElapsedMillis = 600L)
        estimator.observe(beacon, rssi = -45, observedAtElapsedMillis = 700L)
        val snap7 = estimator.snapshot(nowElapsedMillis = 700L)
        assertEquals(-50.0, snap7.observations[0].rssi, 1e-6)
        assertEquals(700L, snap7.observations[0].lastSeenElapsedMillis)
    }

    @Test
    fun rejectsStaleOutOfOrderAndInvalidSamplesAndExpiresAfterFiveSeconds() {
        val estimator = BeaconPositionEstimator()
        val beacon1 = BeaconConfig(id = 1, xCm = 0, yCm = 0, referenceRssi = -59)
        val beacon2 = BeaconConfig(id = 2, xCm = 200, yCm = 0, referenceRssi = -59)

        estimator.observe(beacon1, rssi = -59, observedAtElapsedMillis = 2_000L)

        // Out-of-order sample (1500 < 2000) for beacon 1 must be rejected
        estimator.observe(beacon1, rssi = -99, observedAtElapsedMillis = 1_500L, nowElapsedMillis = 2_100L)
        // Invalid RSSI samples (0, +10, -128) must be rejected
        estimator.observe(beacon1, rssi = 0, observedAtElapsedMillis = 2_200L)
        estimator.observe(beacon1, rssi = 10, observedAtElapsedMillis = 2_300L)
        estimator.observe(beacon1, rssi = -128, observedAtElapsedMillis = 2_400L)

        val checkOrder = estimator.snapshot(nowElapsedMillis = 2_500L)
        assertEquals(1, checkOrder.observations.size)
        assertEquals(-59.0, checkOrder.observations[0].rssi, 1e-6)
        assertEquals(1.0, checkOrder.observations[0].distanceMeters!!, 1e-6)
        assertEquals(2_000L, checkOrder.observations[0].lastSeenElapsedMillis)

        // Stale sample on arrival (age = 6500 - 1000 = 5500 > 5000) must be rejected
        estimator.observe(beacon2, rssi = -59, observedAtElapsedMillis = 1_000L, nowElapsedMillis = 6_500L)
        val at6500 = estimator.snapshot(nowElapsedMillis = 6_500L)
        assertEquals(1, at6500.observations.size)
        assertEquals(1, at6500.observations[0].config.id)

        // Exact 5s boundary (7000 - 2000 == 5000): still fresh
        val at7000 = estimator.snapshot(nowElapsedMillis = 7_000L)
        assertEquals(1, at7000.observations.size)

        // Past 5s expiry (7001 - 2000 == 5001 > 5000): expired
        val at7001 = estimator.snapshot(nowElapsedMillis = 7_001L)
        assertTrue(at7001.observations.isEmpty())
        assertTrue(at7001.position is BeaconPosition.Unavailable)
    }

    @Test
    fun conflictingSameIdBeaconsAreExcludedUntilConflictExpiresThenPositionRecovers() {
        val estimator = BeaconPositionEstimator()
        val beacon1Valid = BeaconConfig(id = 1, xCm = 0, yCm = 0, referenceRssi = -59)
        val beacon1Rogue = BeaconConfig(id = 1, xCm = 500, yCm = 500, referenceRssi = -59)
        val beacon2 = BeaconConfig(id = 2, xCm = 200, yCm = 0, referenceRssi = -59)
        val beacon3 = BeaconConfig(id = 3, xCm = 0, yCm = 200, referenceRssi = -59)

        // Initial valid fix at t = 1000ms
        estimator.observe(beacon1Valid, rssi = -62, observedAtElapsedMillis = 1_000L)
        estimator.observe(beacon2, rssi = -62, observedAtElapsedMillis = 1_000L)
        estimator.observe(beacon3, rssi = -62, observedAtElapsedMillis = 1_000L)

        val initial = estimator.snapshot(nowElapsedMillis = 1_000L)
        assertTrue(initial.position is BeaconPosition.Available)
        assertFalse(initial.observations.first { it.config.id == 1 }.conflicted)

        // Rogue transmitter with same ID (1) but different coordinates arrives at t = 2000ms
        estimator.observe(beacon1Rogue, rssi = -75, observedAtElapsedMillis = 2_000L)

        val duringConflict = estimator.snapshot(nowElapsedMillis = 2_000L)
        val obs1Conflict = duringConflict.observations.first { it.config.id == 1 }
        assertTrue(obs1Conflict.conflicted)
        assertNull(obs1Conflict.distanceMeters)
        val conflictPosition = duringConflict.position as BeaconPosition.Weighted
        assertEquals(2, conflictPosition.anchorCount)
        assertEquals(1.0, conflictPosition.xMeters, 1e-6)
        assertEquals(1.0, conflictPosition.yMeters, 1e-6)
        assertNull(conflictPosition.fallbackReason)

        // Valid beacons keep transmitting at t = 4000ms and t = 6500ms; rogue beacon stays silent after t = 2000ms
        estimator.observe(beacon1Valid, rssi = -62, observedAtElapsedMillis = 4_000L)
        estimator.observe(beacon2, rssi = -62, observedAtElapsedMillis = 4_000L)
        estimator.observe(beacon3, rssi = -62, observedAtElapsedMillis = 4_000L)

        estimator.observe(beacon1Valid, rssi = -62, observedAtElapsedMillis = 6_500L)
        estimator.observe(beacon2, rssi = -62, observedAtElapsedMillis = 6_500L)
        estimator.observe(beacon3, rssi = -62, observedAtElapsedMillis = 6_500L)

        // At t = 7000ms, rogue observation from t = 2000ms is exactly 5000ms old -> still conflicted
        val at7000 = estimator.snapshot(nowElapsedMillis = 7_000L)
        assertTrue(at7000.observations.first { it.config.id == 1 }.conflicted)
        val boundaryPosition = at7000.position as BeaconPosition.Weighted
        assertEquals(2, boundaryPosition.anchorCount)
        assertEquals(1.0, boundaryPosition.xMeters, 1e-6)
        assertEquals(1.0, boundaryPosition.yMeters, 1e-6)
        assertNull(boundaryPosition.fallbackReason)

        // At t = 7001ms, rogue observation expires (5001ms > 5000ms), valid ID 1 recovers cleanly
        val recovered = estimator.snapshot(nowElapsedMillis = 7_001L)
        val obs1Recovered = recovered.observations.first { it.config.id == 1 }
        assertFalse(obs1Recovered.conflicted)
        assertEquals(beacon1Valid, obs1Recovered.config)
        assertEquals(-62.0, obs1Recovered.rssi, 1e-6)
        assertNotNull(obs1Recovered.distanceMeters)
        assertTrue("Expected position recovery after conflict expiry", recovered.position is BeaconPosition.Available)
        val recoveredPos = recovered.position as BeaconPosition.Available
        assertEquals(1.0, recoveredPos.xMeters, 1e-3)
        assertEquals(1.0, recoveredPos.yMeters, 1e-3)
        assertEquals(3, recoveredPos.anchorCount)
    }
    @Test
    fun snapshotTransitionsThroughProximityWeightedAvailableAndExpiry() {
        val estimator = BeaconPositionEstimator()
        val a = BeaconConfig(id = 1, xCm = 0, yCm = 0)
        val b = BeaconConfig(id = 2, xCm = 200, yCm = 0)
        val c = BeaconConfig(id = 3, xCm = 0, yCm = 200)
        assertTrue(estimator.snapshot(0).position is BeaconPosition.Unavailable)

        estimator.observe(a, -59, 1000)
        val proximity = estimator.snapshot(1000).position as BeaconPosition.Proximity
        assertEquals(a, proximity.anchor.config)
        assertEquals(1.0, proximity.anchor.distanceMeters, 1e-6)

        estimator.observe(b, -59, 2000)
        val equal = estimator.snapshot(2000).position as BeaconPosition.Weighted
        assertEquals(2, equal.anchorCount)
        assertEquals(1.0, equal.xMeters, 1e-6)
        assertEquals(0.0, equal.yMeters, 1e-6)
        assertNull(equal.fallbackReason)

        estimator.observe(c, -59, 3000)
        assertTrue(estimator.snapshot(3000).position is BeaconPosition.Available)
        assertTrue(estimator.snapshot(6000).position is BeaconPosition.Available)
        assertTrue(estimator.snapshot(6001).position is BeaconPosition.Weighted)
        val remaining = estimator.snapshot(7001).position as BeaconPosition.Proximity
        assertEquals(c, remaining.anchor.config)
        assertTrue(estimator.snapshot(8001).position is BeaconPosition.Unavailable)
    }

    @Test
    fun weightedUsesInverseDistanceAndDegenerateStrictSolutionKeepsReason() {
        val estimator = BeaconPositionEstimator()
        val a = BeaconConfig(id = 1, xCm = 0, yCm = 0)
        val b = BeaconConfig(id = 2, xCm = 220, yCm = 0)
        val c = BeaconConfig(id = 3, xCm = 440, yCm = 0)
        estimator.observe(a, -59, 1000)
        estimator.observe(b, -79, 1000)
        val two = estimator.snapshot(1000).position as BeaconPosition.Weighted
        assertEquals(0.2, two.xMeters, 1e-6)
        assertEquals(0.0, two.yMeters, 1e-6)
        assertEquals(2, two.anchorCount)
        assertNull(two.fallbackReason)

        estimator.observe(c, -59, 1000)
        val three = estimator.snapshot(1000).position as BeaconPosition.Weighted
        val strict = solvePosition(listOf(BeaconRange(a, 1.0), BeaconRange(b, 10.0), BeaconRange(c, 1.0)))
            as BeaconPosition.Unavailable
        assertEquals(strict.reason, three.fallbackReason)
        assertEquals(3, three.anchorCount)
        assertTrue(three.xMeters.isFinite())
        assertTrue(three.yMeters.isFinite())
        assertEquals(2.2, three.xMeters, 1e-6)
        assertEquals(0.0, three.yMeters, 1e-6)
    }

}

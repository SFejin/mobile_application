package com.example.safetymonitor.detection.events

import com.example.safetymonitor.network.WorkerEventType
import org.junit.Assert.*
import org.junit.Test

class ZoneWorkChecksTest {
    @Test fun shorteningPreservesConfirmationAndMarksReportAsTest() {
        val checks = workingSession()
        checks.respond(checks.active.single().id, CheckAction.TEST_SHORTEN, null, 100, emptySet())
        assertEquals(1100L, checks.active.single().deadline)
        assertTrue(checks.advance(1099, emptySet()).isEmpty())
        assertEquals(ZoneStage.WORKING, checks.active.single().stage)
        assertTrue(checks.advance(1100, emptySet()).isEmpty())
        assertEquals(ZoneStage.OVERDUE, checks.active.single().stage)
        assertEquals(31100L, checks.active.single().deadline)
        assertTrue(checks.advance(31099, emptySet()).isEmpty())
        val report = checks.advance(31100, emptySet()).single()
        assertTrue(report.sender.endsWith(" · 테스트"))
        assertTrue(report.playReceiptSound)
        assertTrue(checks.advance(32000, emptySet()).isEmpty())
    }
    @Test fun shorteningDoesNotChangeApproachOrStaleWork() {
        val checks = ZoneWorkChecks()
        checks.approach(event, 0)
        val approach = checks.active.single()
        checks.respond(approach.id, CheckAction.TEST_SHORTEN, null, 10, nearby)
        assertEquals(approach, checks.active.single())
        checks.respond(approach.id, CheckAction.START, 5, 20, nearby)
        val working = checks.active.single()
        checks.respond(approach.id, CheckAction.TEST_SHORTEN, null, 30, nearby)
        assertEquals(working, checks.active.single())
    }

    private val beaconId = 12
    private val nearby = setOf(beaconId)
    private val event = DetectedEvent(WorkerEventType.DANGER_ZONE_APPROACH, "ANDROID_DANGER_BEACON_12", "nearby", beaconId)

    @Test fun passingSuppressesReportAndUnansweredApproachReportsSilentlyOnce() {
        val checks = ZoneWorkChecks()
        checks.approach(event, 0)
        checks.respond(checks.active.single().id, CheckAction.PASS, null, 1, nearby)
        assertTrue(checks.active.isEmpty())
        assertTrue(checks.advance(30_000, emptySet()).isEmpty())
        checks.approach(event, 40_000)
        assertTrue(checks.advance(69_999, emptySet()).isEmpty())
        val report = checks.advance(70_000, emptySet()).single()
        assertEquals("위험지역 침입 · 비콘 #12", report.sender)
        assertFalse(report.playReceiptSound)
        assertTrue(checks.advance(71_000, emptySet()).isEmpty())
        assertTrue(checks.active.isEmpty())
    }

    @Test fun workingExpiresIntoConfirmationThenReportsExactlyOnce() {
        val checks = workingSession()
        val work = checks.active.single()
        checks.respond(work.id, CheckAction.FINISH, null, 100, setOf(7))
        assertEquals(ZoneStage.WORKING, checks.active.single().stage)
        assertTrue(checks.advance(300_000, emptySet()).isEmpty())
        val overdue = checks.active.single()
        assertEquals(ZoneStage.OVERDUE, overdue.stage)
        checks.respond(work.id, CheckAction.FINISH, null, 300_001, nearby)
        assertEquals(overdue.id, checks.active.single().id)
        val report = checks.advance(330_000, emptySet()).single()
        assertEquals("위험지역 작업 시간 초과 무응답 · 비콘 #12", report.sender)
        assertTrue(report.playReceiptSound)
        assertTrue(checks.advance(350_000, emptySet()).isEmpty())
    }

    @Test fun nearbyCompletionOrOverdueExtensionAvoidsReport() {
        val checks = workingSession()
        checks.advance(300_000, nearby)
        checks.respond(checks.active.single().id, CheckAction.EXTEND, null, 300_010, nearby)
        assertEquals(600_010L, checks.active.single().deadline)
        checks.respond(checks.active.single().id, CheckAction.FINISH, null, 301_000, nearby)
        assertTrue(checks.advance(650_000, nearby).isEmpty())
        assertTrue(checks.active.isEmpty())
    }

    private fun workingSession() = ZoneWorkChecks().also {
        it.approach(event, 0)
        it.respond(it.active.single().id, CheckAction.START, 5, 0, emptySet())
        assertEquals(ZoneStage.APPROACH, it.active.single().stage)
        it.respond(it.active.single().id, CheckAction.START, 5, 0, nearby)
    }
}

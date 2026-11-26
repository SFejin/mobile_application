package com.example.safetymonitor.detection.events

import com.example.safetymonitor.network.WorkerEventType
import org.junit.Assert.*
import org.junit.Test

class HeartResponseWindowTest {
    private val first = DetectedEvent(WorkerEventType.ABNORMAL_VITAL, "ANDROID_HEART_RATE", "48 bpm")

    @Test fun unansweredCheckEscalatesOnceEvenIfNoFurtherSummaryArrives() {
        val window = HeartResponseWindow()
        window.observe(first, 100)
        window.observe(null, 29_000)
        assertNull(window.expire(30_099))
        assertEquals(first.sender, window.expire(30_100)?.sender)
        assertNull(window.expire(31_100))
    }

    @Test fun acknowledgedCheckDoesNotReopenOnSameReading() {
        val window = HeartResponseWindow()
        window.observe(first, 100)
        window.respond(window.pending!!.id, CheckAction.OK)
        window.observe(first, 10_000)
        assertNull(window.pending)
        assertNull(window.expire(30_100))
        window.observe(first.copy(description = "121 bpm"), 300_000)
        assertNotNull(window.pending)
    }

    @Test fun manualHelpAndConfirmedRepeatClearTheTimeout() {
        val window = HeartResponseWindow()
        window.observe(first, 100)
        assertEquals(first, window.respond(window.pending!!.id, CheckAction.HELP))
        assertNull(window.expire(30_100))
        window.observe(first.copy(), 300_000)
        window.complete()
        assertNull(window.expire(330_000))
    }
}

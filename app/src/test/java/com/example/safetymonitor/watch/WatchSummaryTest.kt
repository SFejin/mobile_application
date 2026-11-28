package com.example.safetymonitor.watch

import org.junit.Assert.*
import org.junit.Test
import com.example.workersafety.link.WatchLinkProtocol.Result

class WatchSummaryTest {
    private val now = 2_000_000L
    private fun payload(): MutableMap<String, Any?> = mutableMapOf(
        "window_start" to 1_700_000L, "timestamp" to now, "interval_minutes" to 5,
        "has_hr" to true, "hr_avg" to 78.5, "hr_count" to 30,
        "has_skin" to true, "skin_c" to 33.2, "skin_count" to 4,
        "has_ambient" to true, "ambient_c" to 25.0)
    private fun sample() = checkNotNull(WatchSummary.parse(payload(), now))

    @Test fun parsesActualWatchPayload() {
        val result = sample()
        assertEquals(78.5, result.heartRate!!, 0.0)
        assertEquals(33.2, result.skinC!!, 0.0)
        assertEquals(30, result.heartCount)
    }
    @Test fun absentSensorsRemainNull() {
        val map = payload().apply {
            put("has_hr", false); remove("hr_avg"); put("hr_count", 0)
            put("has_skin", false); remove("skin_c"); put("skin_count", 0)
            put("has_ambient", false); remove("ambient_c")
        }
        val result = checkNotNull(WatchSummary.parse(map, now))
        assertNull(result.heartRate); assertNull(result.skinC); assertNull(result.ambientC)
    }
    @Test fun invalidFieldsAreRejectedWithoutReplacingValues() {
        for ((key, value) in listOf(
            "hr_avg" to Double.NaN, "skin_c" to Double.POSITIVE_INFINITY,
            "hr_avg" to 0.0, "skin_c" to 99.0, "ambient_c" to -99.0,
            "hr_count" to 0, "skin_count" to -1, "timestamp" to "2000000",
            "has_hr" to "true", "interval_minutes" to 1, "interval_minutes" to 10,
            "window_start" to (now + 1), "timestamp" to (now + 60_001))) {
            assertNull(key, WatchSummary.parse(payload().apply { put(key, value) }, now))
        }
    }
    @Test fun missingRequiredFieldIsRejected() {
        for (key in payload().keys) {
            assertNull(key, WatchSummary.parse(payload().apply { remove(key) }, now))
        }
    }
    @Test fun freshReadingBecomesStaleWithoutAnotherPacket() {
        val result = sample()
        assertFalse(result.stale(now + 420_000L))
        assertTrue(result.stale(now + 420_001L))
        assertFalse(result.copy(intervalMinutes = 10).stale(now + 600_000L))
    }
    @Test fun duplicateAndOutOfOrderPacketsDoNotReplaceLatest() {
        val window = WatchReadingWindow(1_600_000L)
        assertTrue(window.accept("watch-a", sample()))
        assertFalse(window.accept("watch-a", sample()))
        assertFalse(window.accept("watch-a", sample().copy(timestamp = now - 1)))
        assertEquals(now, window.latest!!.timestamp)
    }
    @Test fun reconnectionAcceptsNewerSummaryFromSameWatch() {
        val window = WatchReadingWindow(1_600_000L)
        assertTrue(window.accept("watch-a", sample()))
        assertTrue(window.accept("watch-a", sample().copy(windowStart = now, timestamp = now + 300_000)))
        assertEquals(now + 300_000, window.latest!!.timestamp)
    }
    @Test fun anotherWatchCannotReplaceSourceInSameLogin() {
        val window = WatchReadingWindow(1_600_000L)
        assertTrue(window.accept("watch-a", sample()))
        assertFalse(window.accept("watch-b", sample().copy(timestamp = now + 1)))
        assertEquals("watch-a", window.source)
    }
    @Test fun newLoginRejectsOldAccountAndStraddlingWindows() {
        val window = WatchReadingWindow(now + 1)
        assertFalse(window.accept("watch-a", sample()))
        assertFalse(window.accept("watch-a", sample().copy(timestamp = now + 300_000)))
        assertNull(window.latest)
        assertTrue(window.accept("watch-a", sample().copy(windowStart = now + 1, timestamp = now + 300_000)))
    }
    @Test fun receiptReplayAndAdjacentWindowsAreOrdered() {
        val window = WatchReadingWindow(1_600_000L)
        val first = sample()
        assertEquals(Result.ACCEPTED, window.admit("watch-a", first, "first"))
        assertEquals(Result.ACCEPTED, window.admit("watch-a", first, "first"))
        assertEquals(Result.INVALID_DATA, window.admit("watch-a", first.copy(heartRate = 79.0), "first"))
        assertEquals(Result.NEW_WINDOW_REQUIRED, window.admit("watch-a", first.copy(timestamp = now + 1), "overlap"))
        assertEquals(Result.ACCEPTED, window.admit("watch-a", first.copy(windowStart = now, timestamp = now + 300_000), "next"))
        assertEquals(Result.NEW_WINDOW_REQUIRED, window.admit("watch-a", first, "first"))
    }
}

package com.example.safetymonitor.detection.events

import com.example.safetymonitor.network.*
import com.example.safetymonitor.sos.SosSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class EventSenderTest {
    private val event = DetectedEvent(WorkerEventType.ABNORMAL_VITAL, "ANDROID_SKIN_TEMPERATURE", "test")
    private fun session() = SosSession("http://localhost", 7, "token", Instant.now().plusSeconds(600))
    @Test fun sendsContractAndValidatesWorkerAndEvent() = runBlocking {
        val session = session()
        val now = Instant.now()
        val status = deliverEvent(session, event, now, { true }) { token, request ->
            assertEquals("token", token); assertEquals(now, request.time)
            assertEquals(WorkerEventType.ABNORMAL_VITAL, request.eventType)
            assertEquals("ANDROID_SKIN_TEMPERATURE", request.alertSender)
            WorkerEventInfo(1, 7, "worker", now, now, request.eventType, request.alertSender, null, null, null)
        }
        assertTrue(status.startsWith("서버 접수 완료"))
        val mismatch = deliverEvent(session, event, now, { true }) { _, request ->
            WorkerEventInfo(1, 8, "other", now, now, request.eventType, request.alertSender, null, null, null)
        }
        assertTrue(mismatch.startsWith("응답 불일치"))
    }
    @Test fun revokedExpiredAndDevelopmentSessionsDoNotSend() = runBlocking {
        val invalid = session().also { it.invalidate() }
        val dev = SosSession.developmentLogin(1024, "0000")!!
        for ((s, current) in listOf(invalid to true, session() to false, dev to true)) {
            deliverEvent(s, event, Instant.now(), { current }) { _, _ -> error("Must not send") }
        }
    }
    @Test fun unauthorizedInvalidatesAndIoFailureIsNotRetried() = runBlocking {
        val s = session()
        deliverEvent(s, event, Instant.now(), { true }) { _, _ -> throw WorkerApiException(401, null, "rejected") }
        assertFalse(s.valid())
        var calls = 0
        val status = deliverEvent(session(), event, Instant.now(), { true }) { _, _ -> calls++; throw IOException() }
        assertEquals(1, calls); assertTrue(status.contains("접수 여부 불명"))
    }
    @Test fun cancellationPropagates() = runBlocking {
        try {
            deliverEvent(session(), event, Instant.now(), { true }) { _, _ -> throw CancellationException() }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}

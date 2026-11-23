package com.example.safetymonitor.detection.events

import com.example.safetymonitor.network.*
import com.example.safetymonitor.sos.SosSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.io.IOException

class EventSenderTest {
    private val at=Instant.parse("2026-01-01T00:00:00Z")
    private fun session()=SosSession("http://example.test",77,"token",Instant.MAX)
    private fun event(type: WorkerEventType)=DetectedEvent(type,"ANDROID_TEST","test")
    private fun reply(type: WorkerEventType, worker: Long=77)=WorkerEventInfo(9,worker,"Worker",at,at,type,"ANDROID_TEST",null,null,null)

    @Test fun bothEventTypesUseAuthenticatedWorkerContract() = runBlocking {
        for(type in listOf(WorkerEventType.DANGER_ZONE_APPROACH,WorkerEventType.ABNORMAL_VITAL)) {
            var calls=0
            val result=deliverEvent(session(),event(type),at,{true}) { token, request ->
                calls++;assertEquals("token",token);assertEquals(type,request.eventType);assertEquals(at,request.time)
                reply(type)
            }
            assertEquals(1,calls);assertTrue(result.contains("#9"))
        }
    }
    @Test fun expiredChangedAndDevelopmentSessionsNeverPost(): Unit = runBlocking {
        val expired=session().also{it.invalidate()}
        deliverEvent(expired,event(WorkerEventType.ABNORMAL_VITAL),at,{true}) {_,_->error("must not send")}
        deliverEvent(session(),event(WorkerEventType.ABNORMAL_VITAL),at,{false}) {_,_->error("must not send")}
        SosSession.developmentLogin(1024,"0000")?.let {
            assertTrue(deliverEvent(it,event(WorkerEventType.ABNORMAL_VITAL),at,{true}) {_,_->error("must not send")}.contains("개발 모드"))
        }
    }
    @Test fun authFailureInvalidatesSessionAndNeverRetries() = runBlocking {
        val s=session();var calls=0
        val result=deliverEvent(s,event(WorkerEventType.ABNORMAL_VITAL),at,{true}) {_,_->
            calls++;throw WorkerApiException(401,"EXPIRED","denied")
        }
        assertEquals(1,calls);assertFalse(s.valid());assertTrue(result.contains("401"))
    }
    @Test fun lostResponseAndWrongAccountNeverReportSuccess() = runBlocking {
        var calls=0
        val failed=deliverEvent(session(),event(WorkerEventType.ABNORMAL_VITAL),at,{true}) {_,_->calls++;throw IOException()}
        assertEquals(1,calls);assertTrue(failed.contains("불명"))
        val wrong=deliverEvent(session(),event(WorkerEventType.ABNORMAL_VITAL),at,{true}) {_,_->reply(WorkerEventType.ABNORMAL_VITAL,999)}
        assertTrue(wrong.contains("불명"))
    }
}

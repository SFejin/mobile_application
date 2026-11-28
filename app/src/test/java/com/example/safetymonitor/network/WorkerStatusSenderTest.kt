package com.example.safetymonitor.network

import com.example.safetymonitor.sos.SosSession
import com.example.safetymonitor.watch.WatchSnapshot
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.io.IOException

class WorkerStatusSenderTest {
    private fun session() = SosSession("https://example.invalid", 7, "token", Instant.MAX)
    private fun result(r: WorkerStatusRequest) = WorkerStatusResult(r.monitoringStatus, r.reportedAt,
        r.reportedAt, r.watchLastSeenAt, r.heart, if (r.heart == null) "NOT_RECEIVED" else "FRESH")

    @Test fun startSampleStopStayOrderedAndStopCanBeAwaitedBeforeLogout() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = session()
            val gate = CompletableDeferred<Unit>()
            val sent = mutableListOf<WorkerStatusRequest>()
            val sender = WorkerStatusSender(owner, { session }, { 1000 }) { _, request ->
                if (sent.isEmpty()) gate.await()
                sent += request
                result(request)
            }
            sender.start(session)
            sender.sample(session, WatchSnapshot(receivedAt = 800), HeartAssessment(HeartRateStatus.NORMAL, Instant.ofEpochMilli(700)))
            val stop = sender.stop(session)
            assertFalse(stop.isCompleted)
            sender.sample(session, WatchSnapshot(receivedAt = 900), null)
            assertSame(stop, sender.stop(session))
            gate.complete(Unit)
            stop.await()
            assertEquals(listOf(MonitoringStatus.RUNNING, MonitoringStatus.RUNNING, MonitoringStatus.STOPPED), sent.map { it.monitoringStatus })
            assertEquals(listOf(1000L, 1001L, 1002L), sent.map { it.reportedAt.toEpochMilli() })
            assertEquals(Instant.ofEpochMilli(800), sent[1].watchLastSeenAt)
            assertEquals(Instant.ofEpochMilli(700), sent[1].heart?.measuredAt)
            assertNull(sent.last().heart)
        } finally { owner.cancel() }
    }

    @Test fun networkFailureDoesNotKillNextReportAndUnauthorizedStopsFurtherSending() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = session()
            var attempts = 0
            val sender = WorkerStatusSender(owner, { session }) { _, request ->
                when (++attempts) { 1 -> throw IOException(); 3 -> throw WorkerApiException(401, "AUTH", "denied") }
                result(request)
            }
            sender.start(session)
            sender.sample(session, WatchSnapshot(receivedAt = 1), null)
            assertEquals("상태 보고 완료", sender.state.value)
            sender.sample(session, WatchSnapshot(receivedAt = 2), null)
            assertFalse(session.valid())
            sender.stop(session).await()
            assertEquals(3, attempts)
        } finally { owner.cancel() }
    }

    @Test fun queuedOldAccountReportsAreDiscarded() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val old = session()
            var current = old
            val gate = CompletableDeferred<Unit>()
            val sent = mutableListOf<SosSession>()
            val sender = WorkerStatusSender(owner, { current }) { session, request ->
                if (sent.isEmpty()) { sent += session; gate.await() } else sent += session
                result(request)
            }
            sender.start(old)
            sender.sample(old, WatchSnapshot(receivedAt = 1), null)
            current = session()
            sender.start(current)
            val done = sender.stop(current)
            gate.complete(Unit)
            done.await()
            assertEquals(listOf(old, current, current), sent)
        } finally { owner.cancel() }
    }
}

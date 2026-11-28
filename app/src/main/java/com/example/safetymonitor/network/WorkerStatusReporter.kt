package com.example.safetymonitor.network

import com.example.safetymonitor.sos.SosSession
import com.example.safetymonitor.sos.SosStore
import com.example.safetymonitor.watch.WatchSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.time.Instant

/** Main-thread entry points; one process-owned sender preserves start/sample/stop order. */
object WorkerStatusReporter : WorkerStatusSender(
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    currentSession = { SosStore.session.value }
)

open class WorkerStatusSender(
    scope: CoroutineScope,
    private val currentSession: () -> SosSession?,
    private val now: () -> Long = System::currentTimeMillis,
    private val send: suspend (SosSession, WorkerStatusRequest) -> WorkerStatusResult = { session, request ->
        WorkerApiClient(session.server, session.server.startsWith("http://")).reportStatus(session.token, request)
    }
) {
    private data class Pending(val session: SosSession, val request: WorkerStatusRequest, val done: CompletableDeferred<Unit>)
    private val queue = Channel<Pending>(Channel.UNLIMITED)
    private var running: SosSession? = null
    private var lastReported = 0L
    private var stopping: Pair<SosSession, Deferred<Unit>>? = null
    private val mutable = MutableStateFlow("상태 보고 대기")
    val state = mutable.asStateFlow()

    init {
        scope.launch {
            for (item in queue) {
                try {
                    val session = item.session
                    if (currentSession() !== session || !session.valid() || session.isDevelopment) continue
                    val result = send(session, item.request)
                    if (currentSession() === session) mutable.value =
                        if (result.monitoringStatus == item.request.monitoringStatus && result.reportedAt == item.request.reportedAt)
                            "상태 보고 완료" else "상태 응답 불일치 · 서버 확인 필요"
                } catch (e: CancellationException) { throw e
                } catch (e: WorkerApiException) {
                    if (e.httpStatus == 401) item.session.invalidate()
                    if (currentSession() === item.session) mutable.value = "상태 보고 거절 · HTTP ${e.httpStatus}"
                } catch (_: IOException) {
                    if (currentSession() === item.session) mutable.value = "상태 보고 통신 오류 · 다음 새 상태에서 다시 전송"
                } finally { item.done.complete(Unit) }
            }
        }
    }

    private fun enqueue(session: SosSession, status: MonitoringStatus, watch: WatchSnapshot? = null,
        heart: HeartAssessment? = null): Deferred<Unit> {
        val done = CompletableDeferred<Unit>()
        if (!session.valid() || session.isDevelopment || currentSession() !== session) {
            done.complete(Unit)
            return done
        }
        // Millisecond server precision: even rapid start/stop must have distinct timestamps.
        lastReported = maxOf(now(), lastReported + 1)
        val request = WorkerStatusRequest(status, Instant.ofEpochMilli(lastReported),
            watch?.receivedAt?.let(Instant::ofEpochMilli), heart)
        queue.trySend(Pending(session, request, done))
        return done
    }

    fun start(session: SosSession) {
        if (running === session) return
        running = session
        stopping = null
        mutable.value = if (session.isDevelopment) "개발 모드 · 서버 전송 없음" else "상태 보고 중"
        enqueue(session, MonitoringStatus.RUNNING)
    }

    fun sample(session: SosSession, watch: WatchSnapshot, heart: HeartAssessment?) {
        if (running === session) enqueue(session, MonitoringStatus.RUNNING, watch, heart)
    }

    fun stop(session: SosSession): Deferred<Unit> {
        stopping?.takeIf { it.first === session }?.let { return it.second }
        if (running === session) running = null
        return enqueue(session, MonitoringStatus.STOPPED).also { stopping = session to it }
    }
}

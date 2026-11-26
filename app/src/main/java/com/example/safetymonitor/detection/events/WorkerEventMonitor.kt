package com.example.safetymonitor.detection.events

import android.os.SystemClock
import com.example.safetymonitor.detection.beacon.BeaconMonitoringStore
import com.example.safetymonitor.network.WorkerApiClient
import com.example.safetymonitor.sos.SosSession
import com.example.safetymonitor.sos.SosStore
import com.example.safetymonitor.watch.WatchDataRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

data class WorkerEventRecord(val id: Long, val event: DetectedEvent, val at: Instant, val status: String)
object WorkerEventStore {
    private val mutable = MutableStateFlow<List<WorkerEventRecord>>(emptyList())
    val state = mutable.asStateFlow()
    private var nextId = 0L
    fun clear() { mutable.value = emptyList() }
    fun add(event: DetectedEvent, at: Instant): WorkerEventRecord {
        val record = WorkerEventRecord(++nextId, event, at, "전송 대기")
        mutable.value = (listOf(record) + mutable.value).take(20)
        return record
    }
    fun update(id: Long, status: String) { mutable.value = mutable.value.map { if (it.id == id) it.copy(status = status) else it } }
}

/** Service-owned Main scope: keeps monitoring while the Activity is backgrounded. */
class WorkerEventMonitor(private val feedback: SafetyCheckFeedback) {
    private var scope: CoroutineScope? = null
    private var binding: SosSession? = null
    private val owned = mutableSetOf<Long>()
    fun bind(session: SosSession) {
        if (binding === session) return
        close()
        binding = session
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = owner
        val queue = Channel<WorkerEventRecord>(32)
        fun send(event: DetectedEvent) {
            val record = WorkerEventStore.add(event, Instant.now())
            owned.retainAll(WorkerEventStore.state.value.map { it.id }.toSet())
            owned.add(record.id)
            if (!queue.trySend(record).isSuccess) WorkerEventStore.update(record.id, "전송 안 됨 · 대기열 가득 참")
        }
        owner.launch {
            try {
                for (record in queue) {
                    if (SosStore.session.value !== session) continue
                    WorkerEventStore.update(record.id, "전송 중")
                    var accepted = false
                    val status = deliverEvent(session, record.event, record.at,
                        { SosStore.session.value === session }, onAccepted = { accepted = true }) { token, request ->
                        WorkerApiClient(session.server, session.server.startsWith("http://")).createEvent(token, request)
                    }
                    if (SosStore.session.value === session) {
                        WorkerEventStore.update(record.id, status)
                        if (!session.isDevelopment) feedback.result(record.event, status, accepted)
                    }
                }
            } finally { queue.cancel() }
        }
        val vital = VitalRule(EventSettings.state.value)
        val danger = DangerZoneRule(quickEntry = true)
        val zones = ZoneWorkChecks()
        val heart = HeartResponseWindow()
        fun current() = SosStore.session.value === session && session.valid()
        fun publishChecks() {
            val next = SafetyChecks(heart.pending, zones.active)
            SafetyCheckStore.publish(next)
            feedback.update(next)
        }
        fun sendHeart(event: DetectedEvent) {
            vital.markHeartReported()
            send(event)
        }
        SafetyCheckStore.handler = respond@{ id, action, minutes ->
            if (!current()) return@respond
            val now = SystemClock.elapsedRealtime()
            val nearby = nearbyDangerBeacons(BeaconMonitoringStore.state.value, now)
            zones.advance(now, nearby).forEach(::send)
            heart.expire(now)?.let(::sendHeart)
            if (heart.pending?.id == id) heart.respond(id, action)?.let(::sendHeart)
            else zones.respond(id, action, minutes, now, nearby)
            publishChecks()
        }
        owner.launch {
            while (isActive && current()) {
                val now = SystemClock.elapsedRealtime()
                val snapshot = BeaconMonitoringStore.state.value
                val nearby = nearbyDangerBeacons(snapshot, now)
                danger.evaluate(snapshot, now).forEach { zones.approach(it, now) }
                zones.advance(now, nearby).forEach(::send)
                heart.expire(now)?.let(::sendHeart)
                for (event in vital.evaluate(WatchDataRepository.state.value.summary, System.currentTimeMillis())) {
                    if (event.sender == "ANDROID_HEART_RATE") heart.complete()
                    send(event)
                }
                heart.observe(vital.heartCheck, now)
                publishChecks()
                delay(1_000)
            }
            SafetyCheckStore.publish(SafetyChecks())
            feedback.update(SafetyChecks())
        }
    }
    fun close() {
        scope?.cancel(); scope = null; binding = null
        SafetyCheckStore.handler = null
        SafetyCheckStore.publish(SafetyChecks())
        feedback.clear()
        WorkerEventStore.state.value.filter { it.id in owned && it.status in setOf("전송 대기", "전송 중") }
            .forEach { WorkerEventStore.update(it.id, "감시 종료 · 접수 여부 확인 필요") }
        owned.clear()
    }
}

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
class WorkerEventMonitor {
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
        owner.launch {
            try {
                for (record in queue) {
                    if (SosStore.session.value !== session) continue
                    WorkerEventStore.update(record.id, "전송 중")
                    val status = deliverEvent(session, record.event, record.at,
                        { SosStore.session.value === session },
                        { token, request -> WorkerApiClient(session.server, session.server.startsWith("http://")).createEvent(token, request) })
                    if (SosStore.session.value === session) WorkerEventStore.update(record.id, status)
                }
            } finally { queue.cancel() }
        }
        owner.launch {
            var config = EventSettings.state.value
            var vital = VitalRule(config)
            val danger = DangerZoneRule()
            while (isActive) {
                if (SosStore.session.value === session && session.valid()) {
                    val latest = EventSettings.state.value
                    if (latest != config) { config = latest; vital = VitalRule(config) }
                    val now = System.currentTimeMillis()
                    val events = danger.evaluate(BeaconMonitoringStore.state.value, SystemClock.elapsedRealtime()) +
                        vital.evaluate(WatchDataRepository.state.value.summary, now)
                    for (event in events) {
                        val record = WorkerEventStore.add(event, Instant.ofEpochMilli(now))
                        owned.retainAll(WorkerEventStore.state.value.map { it.id }.toSet())
                        owned.add(record.id)
                        if (!queue.trySend(record).isSuccess) WorkerEventStore.update(record.id, "전송 안 됨 · 대기열 가득 참")
                    }
                }
                delay(1_000)
            }
        }
    }
    fun close() {
        scope?.cancel(); scope = null; binding = null
        WorkerEventStore.state.value.filter { it.id in owned && it.status in setOf("전송 대기", "전송 중") }
            .forEach { WorkerEventStore.update(it.id, "감시 종료 · 접수 여부 확인 필요") }
        owned.clear()
    }
}

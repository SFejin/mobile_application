package com.example.safetymonitor.detection.events

import android.os.SystemClock
import com.example.safetymonitor.detection.beacon.BeaconMonitoringStore
import com.example.safetymonitor.network.WorkerApiClient
import com.example.safetymonitor.network.WorkerStatusReporter
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
    val heartStatus = MutableStateFlow("심박수 수신 대기")
    val skinStatus = MutableStateFlow("손목 온도 기준선 준비 중 · 온도 판단 대기")
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
    private var testSender: ((DetectedEvent) -> Unit)? = null
    private var lastTestAt = 0L
    fun testVital(skin: Boolean) {
        val session = binding ?: return
        if (SosStore.session.value !== session || !session.valid()) return
        val now = SystemClock.elapsedRealtime()
        if (lastTestAt != 0L && now - lastTestAt < 10_000L) return
        lastTestAt = now
        testSender?.invoke(vitalTestEvent(skin, System.currentTimeMillis()))
    }
    private val owned = mutableSetOf<Long>()
    fun bind(session: SosSession) {
        if (binding === session) return
        close()
        binding = session
        WorkerStatusReporter.start(session)
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
        testSender = ::send
        val vital = VitalRule(EventSettings.state.value)
        val danger = DangerZoneRule(quickEntry = true)
        val zones = ZoneWorkChecks()
        var lastSummaryTime: Long? = null
        fun current() = SosStore.session.value === session && session.valid()
        fun publishChecks() {
            val next = SafetyChecks(null, zones.active)
            SafetyCheckStore.publish(next)
            feedback.update(next)
        }
        SafetyCheckStore.handler = respond@{ id, action, minutes ->
            if (!current()) return@respond
            val now = SystemClock.elapsedRealtime()
            val nearby = nearbyDangerBeacons(BeaconMonitoringStore.state.value, now)
            zones.advance(now, nearby).forEach(::send)
            zones.respond(id, action, minutes, now, nearby)
            publishChecks()
        }
        owner.launch {
            while (isActive && current()) {
                val now = SystemClock.elapsedRealtime()
                val snapshot = BeaconMonitoringStore.state.value
                val nearby = nearbyDangerBeacons(snapshot, now)
                danger.evaluate(snapshot, now).forEach { zones.approach(it, now) }
                zones.advance(now, nearby).forEach(::send)
                val watch = WatchDataRepository.state.value
                val sample = watch.summary
                if (sample != null && sample.timestamp != lastSummaryTime) {
                    lastSummaryTime = sample.timestamp
                    vital.evaluate(sample, System.currentTimeMillis()).forEach(::send)
                    WorkerStatusReporter.sample(session, watch, vital.heartAssessment)
                }
                WorkerEventStore.heartStatus.value = if (sample == null || sample.stale(System.currentTimeMillis()) || sample.timestamp > System.currentTimeMillis() + 60_000 || sample.intervalMinutes != 5)
                    "심박수 수신 지연 · 판단 대기" else vital.heartStatus
                WorkerEventStore.skinStatus.value = if (sample == null || sample.stale(System.currentTimeMillis()) || sample.timestamp > System.currentTimeMillis() + 60_000 || sample.intervalMinutes != 5)
                    "손목·주변 온도 수신 지연 · 온도 판단 대기" else vital.skinStatus
                publishChecks()
                delay(1_000)
            }
            SafetyCheckStore.publish(SafetyChecks())
            feedback.update(SafetyChecks())
        }
    }
    fun close() {
        WorkerEventStore.heartStatus.value = "심박수 수신 대기"
        WorkerEventStore.skinStatus.value = "손목 온도 기준선 준비 중 · 온도 판단 대기"
        testSender = null
        lastTestAt = 0L
        binding?.let { WorkerStatusReporter.stop(it) }
        scope?.cancel(); scope = null; binding = null
        SafetyCheckStore.handler = null
        SafetyCheckStore.publish(SafetyChecks())
        feedback.clear()
        WorkerEventStore.state.value.filter { it.id in owned && it.status in setOf("전송 대기", "전송 중") }
            .forEach { WorkerEventStore.update(it.id, "감시 종료 · 접수 여부 확인 필요") }
        owned.clear()
    }
}

package com.example.safetymonitor.detection.events

import com.example.safetymonitor.detection.beacon.*
import com.example.safetymonitor.network.WorkerEventType
import com.example.safetymonitor.network.HEART_RATE_ALERT_SENDER
import com.example.safetymonitor.network.HeartAssessment
import com.example.safetymonitor.network.HeartRateStatus
import java.time.Instant
import com.example.safetymonitor.watch.WatchSummary

data class VitalLimits(val low: Double, val high: Double) {
    init { require(low.isFinite() && high.isFinite() && low < high) }
}
enum class SkinEnvironment(val label: String, val demoLimits: VitalLimits) {
    COLD("저온 환경", VitalLimits(24.0, 34.0)),
    HOT("고온 환경", VitalLimits(30.0, 38.0)),
    NORMAL("일반 환경", VitalLimits(27.0, 36.0))
}
data class SkinProfiles(val limits: Map<SkinEnvironment, VitalLimits> = emptyMap()) {
    fun updated(environment: SkinEnvironment, value: VitalLimits?): SkinProfiles =
        copy(limits = if (value == null) limits - environment else limits + (environment to value))
    fun config(heart: VitalLimits?, environment: SkinEnvironment) = EventConfig(heart, limits[environment], environment)
}
data class EventConfig(val heart: VitalLimits? = null, val skin: VitalLimits? = null,
    val skinEnvironment: SkinEnvironment = SkinEnvironment.NORMAL, val skinRise: Double? = null) {
    init {
        require(heart == null || (heart.low >= 35 && heart.high <= 230))
        require(skin == null || (skin.low >= 10 && skin.high <= 50))
    }
}
data class DetectedEvent(
    val type: WorkerEventType,
    val sender: String,
    val description: String,
    val beaconId: Int? = null,
    val playReceiptSound: Boolean = true
)

/** One alert per abnormal episode; two distinct contiguous windows confirm a direction. */
class VitalRule(private val config: EventConfig) {
    private class Track {
        var direction = 0
        var count = 0
        var alerted = false
        fun evaluate(value: Double?, limits: VitalLimits?, contiguous: Boolean, upperInclusive: Boolean = false): Boolean {
            if (value == null || !value.isFinite() || limits == null) {
                count = 0
                return false // Missing measurements do not prove recovery.
            }
            val next = when { value < limits.low -> -1; value > limits.high || upperInclusive && value == limits.high -> 1; else -> 0 }
            if (next == 0) { direction = 0; count = 0; alerted = false; return false }
            count = if (contiguous && next == direction) count + 1 else 1
            direction = next
            if (count < 2 || alerted) return false
            alerted = true
            return true
        }
    }
    var heartStatus = "심박수 수신 대기"
        private set
    private var previous: WatchSummary? = null
    private val heart = Track()
    private val skin = Track()
    private val skinTrend = SkinTrendRule()
    val skinBaseline: Double? get() = skinTrend.baseline
    val skinStatus: String get() = skinTrend.status
    var heartAssessment: HeartAssessment? = null
        private set
    var heartCheck: DetectedEvent? = null
        private set

    fun markHeartReported() {
        heart.alerted = heart.direction != 0
        heartCheck = null
    }

    fun evaluate(summary: WatchSummary?, now: Long): List<DetectedEvent> {
        heartAssessment = null
        if (summary == null || summary.intervalMinutes != 5 || summary.stale(now) || summary.timestamp > now + 60_000) {
            heartCheck = null
            return emptyList()
        }
        val last = previous
        if (last != null && summary.timestamp <= last.timestamp) return emptyList()
        val contiguous = last != null && summary.windowStart >= last.timestamp &&
            summary.windowStart - last.timestamp <= 60_000 && summary.intervalMinutes == last.intervalMinutes
        previous = summary
        val result = mutableListOf<DetectedEvent>()
        val hr = summary.heartRate?.takeIf { it in 35.0..230.0 && summary.heartCount >= 3 }
        val temperature = summary.skinC?.takeIf { it in 10.0..50.0 && summary.skinCount > 0 }
        val heartEvent = DetectedEvent(WorkerEventType.ABNORMAL_VITAL, HEART_RATE_ALERT_SENDER, "심박수 요약 이상 · $hr bpm")
        val sendHeart = heart.evaluate(hr, config.heart, contiguous)
        if (hr != null && config.heart != null) {
            val status = when {
                heart.direction == 0 -> HeartRateStatus.NORMAL
                heart.count >= 2 -> HeartRateStatus.DANGER
                else -> null
            }
            heartAssessment = status?.let { HeartAssessment(it, Instant.ofEpochMilli(summary.timestamp)) }
        }
        heartCheck = heartEvent.takeIf { heart.count == 1 && !heart.alerted }
        heartStatus = when {
            hr == null -> "심박수 측정 부족 · 판단 대기"
            heart.direction == 0 -> "심박수 정상 범위"
            heart.count >= 2 -> "심박수 이상 감지 · 연속 두 구간"
            else -> "심박수 이상 의심 · 다음 구간 확인 중"
        }
        if (sendHeart) result += heartEvent
        if (if (config.skinRise != null) skinTrend.evaluate(temperature, contiguous, config.skinRise, summary.ambientC)
            else skin.evaluate(temperature, config.skin, contiguous, upperInclusive = true)) result += DetectedEvent(
            WorkerEventType.ABNORMAL_VITAL, "체온 이상 SOS", "손목 피부 온도 이상 · $temperature °C · 주변 ${summary.ambientC ?: "측정 없음"} °C")
        return result
    }
}

/** Fresh danger packets only. Absence is unknown; rearm requires observed distance >= 3m. */
class DangerZoneRule(private val quickEntry: Boolean = false) {
    private class Track {
        var last = -1L; var since = -1L; var count = 0; var active = false
        fun resetRun() { since = -1; count = 0 }
    }
    private val tracks = mutableMapOf<Int, Track>()
    fun evaluate(snapshot: BeaconSnapshot, now: Long): List<DetectedEvent> {
        val valid = if (snapshot.role == BeaconRole.RECEIVER && snapshot.status == BeaconStatus.ACTIVE)
            snapshot.observations.filter { it.config.danger && !it.conflicted &&
                it.distanceMeters?.let { d -> d.isFinite() && d > 0 } == true &&
                it.lastSeenElapsedMillis <= now && now - it.lastSeenElapsedMillis <= 5_000 }
                .groupBy { it.config.id }.filterValues { it.size == 1 }.mapValues { it.value.single() }
            else emptyMap()
        tracks.filterKeys { it !in valid }.values.forEach { it.resetRun() }
        val result = mutableListOf<DetectedEvent>()
        for ((id, sample) in valid) {
            val track = tracks.getOrPut(id) { Track() }
            val at = sample.lastSeenElapsedMillis
            if (at <= track.last) continue
            if (track.last >= 0 && at - track.last > 5_000) track.resetRun()
            track.last = at
            val qualifies = if (track.active) sample.distanceMeters!! >= 3 else sample.distanceMeters!! <= 2
            if (!qualifies) { track.resetRun(); continue }
            if (track.since < 0) track.since = at
            track.count++
            val quickApproach = quickEntry && !track.active
            if (!quickApproach && (track.count < 3 || at - track.since < 3_000)) continue
            track.active = !track.active
            track.resetRun()
            if (track.active) result += DetectedEvent(WorkerEventType.DANGER_ZONE_APPROACH,
                "ANDROID_DANGER_BEACON_$id", "위험 비콘 #$id · 추정 거리 2m 이내 접근", beaconId = id)
        }
        return result
    }
}

internal fun nearbyDangerBeacons(snapshot: BeaconSnapshot, now: Long): Set<Int> {
    if (snapshot.role != BeaconRole.RECEIVER || snapshot.status != BeaconStatus.ACTIVE) return emptySet()
    return snapshot.observations.groupBy { it.config.id }.mapNotNull { (id, observations) ->
        val sample = observations.singleOrNull() ?: return@mapNotNull null
        val distance = sample.distanceMeters ?: return@mapNotNull null
        id.takeIf { sample.config.danger && !sample.conflicted && distance > 0 && distance <= 2.0 &&
            now - sample.lastSeenElapsedMillis in 0..5_000 }
    }.toSet()
}

/** User-facing cause, independent of network event codes and test suffixes. */
val DetectedEvent.statusLabel: String
    get() = when {
        type == WorkerEventType.FALL_DETECTED -> "낙상"
        sender.startsWith("심박수") || sender == "ANDROID_HEART_RATE" -> "심박수 이상"
        sender.startsWith("체온") || sender == "ANDROID_SKIN_TEMPERATURE" -> "체온 이상"
        type == WorkerEventType.DANGER_ZONE_APPROACH && playReceiptSound -> "위험구역 작업 시간 초과"
        type == WorkerEventType.DANGER_ZONE_APPROACH -> "위험구역 접근"
        else -> "생체 신호 이상"
    }

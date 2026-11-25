package com.example.safetymonitor.detection.events

import com.example.safetymonitor.detection.beacon.*
import com.example.safetymonitor.network.WorkerEventType
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
    val skinEnvironment: SkinEnvironment = SkinEnvironment.NORMAL) {
    init {
        require(heart == null || (heart.low >= 35 && heart.high <= 230))
        require(skin == null || (skin.low >= 10 && skin.high <= 50))
    }
}
data class DetectedEvent(val type: WorkerEventType, val sender: String, val description: String)

/** One alert per abnormal episode; two distinct contiguous windows confirm a direction. */
class VitalRule(private val config: EventConfig) {
    private class Track {
        var direction = 0
        var count = 0
        var alerted = false
        fun evaluate(value: Double?, limits: VitalLimits?, contiguous: Boolean): Boolean {
            if (value == null || !value.isFinite() || limits == null) {
                count = 0
                return false // Missing measurements do not prove recovery.
            }
            val next = when { value < limits.low -> -1; value > limits.high -> 1; else -> 0 }
            if (next == 0) { direction = 0; count = 0; alerted = false; return false }
            count = if (contiguous && next == direction) count + 1 else 1
            direction = next
            if (count < 2 || alerted) return false
            alerted = true
            return true
        }
    }
    private var previous: WatchSummary? = null
    private val heart = Track()
    private val skin = Track()
    fun evaluate(summary: WatchSummary?, now: Long): List<DetectedEvent> {
        if (summary == null || summary.stale(now) || summary.timestamp > now + 60_000) return emptyList()
        val last = previous
        if (last != null && summary.timestamp <= last.timestamp) return emptyList()
        val contiguous = last != null && summary.windowStart >= last.timestamp &&
            summary.windowStart - last.timestamp <= 60_000 && summary.intervalMinutes == last.intervalMinutes
        previous = summary
        val result = mutableListOf<DetectedEvent>()
        val hr = summary.heartRate?.takeIf { it in 35.0..230.0 && summary.heartCount >= 3 }
        val temperature = summary.skinC?.takeIf { it in 10.0..50.0 && summary.skinCount > 0 }
        if (heart.evaluate(hr, config.heart, contiguous)) result += DetectedEvent(
            WorkerEventType.ABNORMAL_VITAL, "ANDROID_HEART_RATE", "심박수 요약 이상 · $hr bpm")
        if (skin.evaluate(temperature, config.skin, contiguous)) result += DetectedEvent(
            WorkerEventType.ABNORMAL_VITAL, "ANDROID_SKIN_TEMPERATURE", "피부 온도 이상 · $temperature °C")
        return result
    }
}

/** Fresh danger packets only. Absence is unknown; rearm requires observed distance >= 3m. */
class DangerZoneRule {
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
            if (track.count < 3 || at - track.since < 3_000) continue
            track.active = !track.active
            track.resetRun()
            if (track.active) result += DetectedEvent(WorkerEventType.DANGER_ZONE_APPROACH,
                "ANDROID_DANGER_BEACON_$id", "위험 비콘 #$id · 추정 거리 2m 이내 접근")
        }
        return result
    }
}

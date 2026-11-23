package com.example.safetymonitor.detection.events

import com.example.safetymonitor.detection.beacon.*
import com.example.safetymonitor.network.WorkerEventType
import com.example.safetymonitor.watch.WatchSummary

data class EventConfig(
    val dangerIds: Set<Int> = emptySet(),
    val heartEnabled: Boolean = true,
    val lowHeartRate: Int = 50,
    val highHeartRate: Int = 120
) {
    init {
        require(dangerIds.size <= 16 && dangerIds.all { it in 1..65535 })
        require(lowHeartRate in 35..100 && highHeartRate in 100..230 && highHeartRate - lowHeartRate >= 20)
    }
}

data class DetectedEvent(val type: WorkerEventType, val sender: String, val description: String)

/** Per-beacon episodes; absence is unknown, never evidence of leaving a danger zone. */
class DangerZoneRule(private val ids: Set<Int>) {
    private data class Track(var lastSample: Long = -1, var since: Long = -1, var count: Int = 0,
        var active: Boolean = false, var lastEvent: Long? = null) {
        fun breakRun() { since = -1; count = 0 }
    }
    private val tracks = ids.associateWith { Track() }

    fun evaluate(snapshot: BeaconSnapshot, now: Long): List<DetectedEvent> {
        val results = mutableListOf<DetectedEvent>()
        for ((id, track) in tracks) {
            val sample = snapshot.observations.singleOrNull { it.config.id == id }
            val distance = sample?.distanceMeters
            if (snapshot.role != BeaconRole.RECEIVER || snapshot.status != BeaconStatus.ACTIVE ||
                sample == null || sample.conflicted || distance == null || !distance.isFinite() || distance <= 0 ||
                sample.lastSeenElapsedMillis > now || now - sample.lastSeenElapsedMillis > 5_000) {
                track.breakRun()
                continue
            }
            if (sample.lastSeenElapsedMillis <= track.lastSample) continue
            if (track.lastSample >= 0 && sample.lastSeenElapsedMillis - track.lastSample > 5_000) track.breakRun()
            track.lastSample = sample.lastSeenElapsedMillis
            val qualifies = if (track.active) distance >= 3.0 else distance <= 2.0
            if (!qualifies) { track.breakRun(); continue }
            if (track.since < 0) track.since = track.lastSample
            track.count++
            if (track.count < 3 || track.lastSample - track.since < 3_000) continue
            if (track.active) {
                track.active = false
                track.breakRun()
            } else if (track.lastEvent == null || now - track.lastEvent!! >= 60_000) {
                track.active = true
                track.lastEvent = now
                track.breakRun()
                results += DetectedEvent(WorkerEventType.DANGER_ZONE_APPROACH, "ANDROID_BEACON_$id",
                    "위험 비콘 #$id · 추정 거리 2m 이내 접근")
            }
        }
        return results
    }
}

/** Two distinct, consecutive summary windows in the same direction; no instantaneous HR claims. */
class VitalRule(private val config: EventConfig) {
    private var lastTimestamp = -1L
    private var previous: WatchSummary? = null
    private var direction = 0
    private var count = 0
    private var alerted = false

    fun evaluate(summary: WatchSummary?, now: Long): DetectedEvent? {
        if (!config.heartEnabled) return null
        if (summary == null || summary.stale(now) || summary.timestamp > now + 60_000) {
            previous = null; direction = 0; count = 0
            return null
        }
        if (summary.timestamp <= lastTimestamp) return null
        lastTimestamp = summary.timestamp
        val hr = summary.heartRate
        if (hr == null || !hr.isFinite() || hr !in 35.0..230.0 || summary.heartCount < 3 ||
            summary.intervalMinutes !in setOf(5, 10) || summary.windowStart <= 0 || summary.windowStart >= summary.timestamp) {
            previous = null; direction = 0; count = 0
            return null
        }
        val currentDirection = when {
            hr <= config.lowHeartRate -> -1
            hr >= config.highHeartRate -> 1
            else -> 0
        }
        val before = previous
        val consecutive = before != null && before.intervalMinutes == summary.intervalMinutes &&
            summary.windowStart >= before.timestamp && summary.windowStart - before.timestamp <= 120_000
        previous = summary
        if (currentDirection == 0) {
            count = 0; direction = 0
            if (hr >= config.lowHeartRate + 5 && hr <= config.highHeartRate - 10) alerted = false
            return null
        }
        count = if (consecutive && direction == currentDirection) count + 1 else 1
        direction = currentDirection
        if (alerted || count < 2) return null
        alerted = true
        return DetectedEvent(WorkerEventType.ABNORMAL_VITAL,
            if (direction < 0) "ANDROID_WATCH_HR_LOW" else "ANDROID_WATCH_HR_HIGH",
            "평균 심박 ${hr.toInt()} bpm · ${summary.intervalMinutes}분 요약 2구간 연속 ${if (direction < 0) "하한 이하" else "상한 이상"}")
    }
}

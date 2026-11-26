package com.example.safetymonitor.detection.events

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ZoneStage { APPROACH, WORKING, OVERDUE }
data class HeartCheck(val id: String, val event: DetectedEvent, val deadline: Long)
data class ZoneCheck(val id: String, val beaconId: Int, val event: DetectedEvent,
    val stage: ZoneStage, val deadline: Long, val nearby: Boolean)
data class SafetyChecks(val heart: HeartCheck? = null, val zones: List<ZoneCheck> = emptyList()) {
    val hasPrompt: Boolean get() = heart != null || zones.any { it.stage != ZoneStage.WORKING }
}
enum class CheckAction { OK, HELP, PASS, START, FINISH, EXTEND }

internal val workMinutes = listOf(5, 10, 15, 30)

internal class HeartResponseWindow {
    var pending: HeartCheck? = null
        private set
    private var lastObserved: DetectedEvent? = null

    fun observe(first: DetectedEvent?, now: Long) {
        if (first === lastObserved) return
        lastObserved = first
        if (first != null && pending == null) {
            pending = HeartCheck(UUID.randomUUID().toString(), first, now + 30_000)
        }
    }

    fun expire(now: Long): DetectedEvent? {
        val check = pending?.takeIf { now >= it.deadline } ?: return null
        complete()
        return check.event.copy(description = "${check.event.description} · 상태 확인 무응답")
    }

    fun respond(id: String, action: CheckAction): DetectedEvent? {
        val check = pending?.takeIf { it.id == id } ?: return null
        return when (action) {
            CheckAction.OK -> { complete(); null }
            CheckAction.HELP -> { complete(); check.event }
            else -> null
        }
    }

    fun complete() { pending = null }
}

object SafetyCheckStore {
    private val mutable = MutableStateFlow(SafetyChecks())
    val state = mutable.asStateFlow()
    internal var handler: ((String, CheckAction, Int?) -> Unit)? = null

    fun respond(id: String, action: CheckAction, minutes: Int? = null) {
        handler?.invoke(id, action, minutes)
    }

    internal fun publish(value: SafetyChecks) { mutable.value = value }
}

/** Deadlines use elapsed time so changing the device clock cannot postpone a check. */
internal class ZoneWorkChecks {
    private val checks = linkedMapOf<Int, ZoneCheck>()
    val active: List<ZoneCheck> get() = checks.values.toList()

    fun approach(event: DetectedEvent, now: Long) {
        val beaconId = event.beaconId ?: return
        checks.putIfAbsent(beaconId, ZoneCheck(newId(), beaconId, event, ZoneStage.APPROACH, now + 30_000, true))
    }

    fun advance(now: Long, nearby: Set<Int>): List<DetectedEvent> {
        val expired = mutableListOf<DetectedEvent>()
        val iterator = checks.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val zone = entry.value.copy(nearby = entry.key in nearby)
            if (now < zone.deadline) {
                entry.setValue(zone)
                continue
            }
            when (zone.stage) {
                ZoneStage.APPROACH -> {
                    iterator.remove()
                    expired += zone.event.copy(
                        sender = "ANDROID_DANGER_BEACON_${zone.beaconId}_APPROACH_UNCONFIRMED",
                        description = "위험 비콘 #${zone.beaconId} · 접근 확인 무응답",
                        playReceiptSound = false
                    )
                }
                ZoneStage.WORKING -> entry.setValue(zone.copy(id = newId(), stage = ZoneStage.OVERDUE, deadline = now + 30_000))
                ZoneStage.OVERDUE -> {
                    iterator.remove()
                    expired += zone.event.copy(
                        sender = "ANDROID_DANGER_BEACON_${zone.beaconId}_WORK_END_UNCONFIRMED",
                        description = "위험 비콘 #${zone.beaconId} · 작업 종료 확인 무응답"
                    )
                }
            }
        }
        return expired
    }

    fun respond(id: String, action: CheckAction, minutes: Int?, now: Long, nearby: Set<Int>) {
        val zone = checks.values.firstOrNull { it.id == id && now < it.deadline } ?: return
        val isNearby = zone.beaconId in nearby
        when {
            zone.stage == ZoneStage.APPROACH && action == CheckAction.PASS -> checks.remove(zone.beaconId)
            zone.stage == ZoneStage.APPROACH && action == CheckAction.START && isNearby && minutes != null && minutes in workMinutes ->
                checks[zone.beaconId] = zone.copy(id = newId(), stage = ZoneStage.WORKING,
                    deadline = now + minutes * 60_000L, nearby = true)
            action == CheckAction.FINISH && (zone.stage == ZoneStage.OVERDUE || zone.stage == ZoneStage.WORKING && isNearby) ->
                checks.remove(zone.beaconId)
            zone.stage == ZoneStage.OVERDUE && action == CheckAction.EXTEND ->
                checks[zone.beaconId] = zone.copy(id = newId(), stage = ZoneStage.WORKING,
                    deadline = now + 5 * 60_000L, nearby = isNearby)
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()
}

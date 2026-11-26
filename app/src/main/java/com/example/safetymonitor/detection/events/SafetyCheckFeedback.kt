package com.example.safetymonitor.detection.events

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import com.example.safetymonitor.MainActivity
import com.example.safetymonitor.R
import com.example.safetymonitor.sos.SosController

class SafetyCheckFeedback(private val service: Service, private val sos: SosController) {
    private val manager = service.getSystemService(NotificationManager::class.java)
    private val shown = mutableMapOf<String, Int>()
    private var nextId = 2_000

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "작업·심박 확인", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        })
    }

    fun update(checks: SafetyChecks) {
        val prompts = mutableMapOf<String, Pair<String, String>>()
        checks.heart?.let { prompts[it.id] = "심박수 상태 확인" to "30초 안에 응답이 없으면 SOS를 전송합니다." }
        for (zone in checks.zones) {
            when (zone.stage) {
                ZoneStage.APPROACH -> prompts[zone.id] = "위험 비콘 #${zone.beaconId} 접근" to
                    "30초 안에 통과 또는 작업 시작을 선택하세요. 응답이 없으면 관리자에게 알립니다."
                ZoneStage.OVERDUE -> prompts[zone.id] = "위험 비콘 #${zone.beaconId} 작업 종료 확인" to "30초 안에 작업 끝 또는 연장을 선택하세요."
                ZoneStage.WORKING -> Unit
            }
        }
        for (id in shown.keys.toList()) {
            if (id !in prompts) shown.remove(id)?.let(manager::cancel)
        }
        val newPrompts = prompts.filterKeys { it !in shown }
        if (newPrompts.isNotEmpty()) sos.vibrateSafetyCheck()
        for ((id, message) in newPrompts) {
            val notificationId = nextId++
            shown[id] = notificationId
            post(notificationId, message.first, message.second)
        }
    }

    fun result(event: DetectedEvent, status: String, accepted: Boolean) {
        val title = if (event.playReceiptSound) {
            if (accepted) sos.playSafetyReceipt()
            if (accepted) "SOS 서버 접수 완료" else "SOS 전송 상태 확인"
        } else {
            if (accepted) "위험 구역 알림 접수 완료" else "위험 구역 알림 전송 상태 확인"
        }
        val id = if (event.playReceiptSound) RESULT_ID else APPROACH_RESULT_ID
        post(id, title, "${event.description}\n$status")
    }

    fun clear() {
        update(SafetyChecks())
        manager.cancel(RESULT_ID)
        manager.cancel(APPROACH_RESULT_ID)
    }

    private fun post(id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(service, id,
            Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            if (!manager.areNotificationsEnabled()) return
            manager.notify(id, Notification.Builder(service, CHANNEL)
                .setSmallIcon(R.drawable.ic_monitoring)
                .setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build())
        }
    }

    private companion object {
        const val CHANNEL = "worker_safety_checks"
        const val RESULT_ID = 1004
        const val APPROACH_RESULT_ID = 1005
    }
}

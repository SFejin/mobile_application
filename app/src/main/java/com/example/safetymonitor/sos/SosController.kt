package com.example.safetymonitor.sos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.example.safetymonitor.MainActivity
import com.example.safetymonitor.R
import com.example.safetymonitor.detection.fall.FallMonitoringService
import kotlinx.coroutines.*
import java.util.UUID

/** Lives with the existing foreground sensor service, not with an Activity. */
class SosController(private val service: Service) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = service.getSystemService(NotificationManager::class.java)
    private val vibrator = service.getSystemService(Vibrator::class.java)
    private var ringtone: Ringtone? = null
    private val machine: SosStateMachine
    private var target: SosSession? = null
    private var timer: Job? = null

    init {
        SosStore.initialize(service)
        machine = SosStateMachine(SosStore.state.value)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "자동 SOS", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "낙상 후 취소 대기와 서버 접수 결과"
            setSound(null, null)
        })
    }

    fun detect(test: Boolean = false) {
        val session = SosStore.session.value
        if (!machine.detect(UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), System.currentTimeMillis(),
                session?.workerId, session?.server.orEmpty(), test)) return
        target = session
        publish()
        startAlarm()
        timer = scope.launch {
            while (machine.state.phase == SosPhase.COUNTDOWN) {
                delay(200)
                if (machine.tick(SystemClock.elapsedRealtime())) {
                    stopAlarm()
                    publish()
                    dispatch()
                } else publish()
            }
        }
    }

    fun cancel(id: String) {
        if (machine.cancel(id, SystemClock.elapsedRealtime())) {
            timer?.cancel()
            stopAlarm()
            publish()
        }
    }

    private suspend fun dispatch() {
        val incident = machine.state
        val session = target
        val result = when {
            incident.test -> SosPhase.TEST_DONE to "SOS 연습 완료 · 서버로 전송하지 않았습니다."
            session == null -> SosPhase.FAILED to "서버 계정이 연결되지 않아 전송하지 못했습니다. SOS 서버 연결을 설정하세요."
            SosStore.session.value !== session -> SosPhase.FAILED to "서버 계정 연결이 변경되어 전송하지 않았습니다."
            else -> withContext(Dispatchers.IO) { SosServer.send(session, incident) }
        }
        if (machine.finish(incident.incidentId, result.first, result.second, SystemClock.elapsedRealtime())) publish()
    }

    private var lastNotification: Pair<SosPhase, Int>? = null
    private fun publish() {
        val state = machine.state
        SosStore.publish(service, state)
        val key = state.phase to state.remainingSeconds
        if (lastNotification == key) return
        lastNotification = key
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(service, 90,
            Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = if (state.phase == SosPhase.COUNTDOWN) {
            "${if (state.test) "SOS 연습" else "자동 SOS"} · ${state.remainingSeconds}초 후 ${if (state.test) "연습 완료" else "전송"}"
        } else when (state.phase) {
            SosPhase.SENT -> "SOS 서버 접수 완료"
            SosPhase.CANCELLED -> "SOS 취소됨"
            SosPhase.SENDING -> "SOS 전송 중"
            SosPhase.TEST_DONE -> "SOS 연습 완료"
            else -> "SOS 전송 상태 확인 필요"
        }
        val notification = Notification.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_monitoring).setContentTitle(title).setContentText(state.message)
            .setStyle(Notification.BigTextStyle().bigText(state.message)).setContentIntent(open)
            .setCategory(Notification.CATEGORY_ALARM).setOnlyAlertOnce(true)
            .setOngoing(state.active).setAutoCancel(!state.active)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (state.phase == SosPhase.COUNTDOWN) {
            val cancel = PendingIntent.getService(service, state.incidentId.hashCode(),
                Intent(service, FallMonitoringService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, state.incidentId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notification.addAction(Notification.Action.Builder(null, "괜찮아요 · 취소", cancel).build())
        }
        manager.notify(NOTIFICATION_ID, notification.build())
    }

    private fun startAlarm() {
        runCatching {
            ringtone = RingtoneManager.getRingtone(service, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 700), 0))
        }
    }

    private fun stopAlarm() { ringtone?.stop(); ringtone = null; vibrator.cancel() }

    fun close() {
        if (machine.state.phase == SosPhase.COUNTDOWN) cancel(machine.state.incidentId)
        if (machine.state.phase == SosPhase.SENDING) {
            machine.finish(machine.state.incidentId, SosPhase.UNKNOWN,
                "전송 중 감시가 종료되었습니다. 관리자 화면에서 접수 여부를 확인하세요.", SystemClock.elapsedRealtime())
            publish()
        }
        stopAlarm()
        scope.cancel()
    }

    companion object {
        const val ACTION_CANCEL = "com.example.safetymonitor.SOS_CANCEL"
        const val ACTION_TEST = "com.example.safetymonitor.SOS_LOCAL_TEST"
        const val EXTRA_ID = "incidentId"
        private const val CHANNEL = "auto_sos"
        private const val NOTIFICATION_ID = 1003
    }
}

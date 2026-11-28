package com.example.safetymonitor.sos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
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
    private val alarmAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var alarmPlayer: MediaPlayer? = null
    private var pendingSafetyReceipt = false
    private var pendingReceiptReason = "낙상"
    private val machine: SosStateMachine
    private var target: SosSession? = null
    private var timer: Job? = null

    init {
        ReceiptAlarmStore.onSilence = { silenceReceipt() }
        SosStore.initialize(service)
        machine = SosStateMachine(SosStore.state.value)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "자동 SOS", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "낙상 후 취소 대기와 서버 접수 결과"
            setSound(null, null)
        })
    }

    fun detect(test: Boolean = false) {
        val session = SosStore.session.value?.takeIf { it.valid() } ?: return
        if (!machine.detect(UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), System.currentTimeMillis(),
                session.workerId, session.server, test || session.isDevelopment)) return
        target = session
        publish()
        startCountdownVibration()
        timer = scope.launch {
            while (machine.state.phase == SosPhase.COUNTDOWN) {
                delay(200)
                if (machine.tick(SystemClock.elapsedRealtime())) {
                    stopCountdownVibration()
                    flushSafetyReceipt()
                    publish()
                    dispatch()
                } else publish()
            }
        }
    }

    fun cancel(id: String) {
        if (machine.cancel(id, SystemClock.elapsedRealtime())) {
            timer?.cancel()
            stopCountdownVibration()
            flushSafetyReceipt()
            publish()
        }
    }

    private suspend fun dispatch() {
        val incident = machine.state
        val session = target
        val result = when {
            incident.test -> SosPhase.TEST_DONE to "SOS 연습 완료 · 서버로 전송하지 않았습니다."
            session == null -> SosPhase.FAILED to "서버 계정이 연결되지 않아 전송하지 못했습니다. 앱에 다시 로그인하세요."
            else -> withContext(Dispatchers.IO) {
                if (SosStore.session.value !== session) {
                    SosPhase.FAILED to "서버 계정 연결이 변경되어 전송하지 않았습니다."
                } else SosServer.send(session, incident)
            }
        }
        if (machine.finish(incident.incidentId, result.first, result.second, SystemClock.elapsedRealtime())) {
            publish()
            if (machine.state.phase == SosPhase.SENT) playReceiptSound()
        }
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

    @Suppress("DEPRECATION") // AudioAttributes keeps alarm vibration available on Android 8+.
    private fun startCountdownVibration() {
        if (ReceiptAlarmStore.active.value != null) {
            pendingSafetyReceipt = true
            pendingReceiptReason = ReceiptAlarmStore.reason.value
        }
        ReceiptAlarmStore.clear()
        releaseAlarmPlayer()
        runCatching {
            val timings = longArrayOf(0, 850, 250)
            val effect = if (vibrator.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(timings, intArrayOf(0, 255, 0), 0)
            } else {
                VibrationEffect.createWaveform(timings, 0)
            }
            vibrator.vibrate(effect, alarmAttributes)
        }
    }

    private fun playReceiptSound(reason: String = "낙상") {
        releaseAlarmPlayer()
        ReceiptAlarmStore.show(UUID.randomUUID().toString(), reason)
        runCatching {
            val player = MediaPlayer.create(service, R.raw.sos_emergency_alarm, alarmAttributes,
                AudioManager.AUDIO_SESSION_ID_GENERATE)
            if (player != null) {
                alarmPlayer = player
                player.setOnErrorListener { failed, _, _ ->
                    if (alarmPlayer === failed) releaseAlarmPlayer()
                    true
                }
                player.isLooping = true
                player.start()
            }
        }.onFailure { releaseAlarmPlayer() }
    }

    private fun silenceReceipt() {
        ReceiptAlarmStore.clear()
        releaseAlarmPlayer()
    }

    private fun releaseAlarmPlayer() {
        val player = alarmPlayer
        alarmPlayer = null
        runCatching { player?.release() }
    }

    private fun stopCountdownVibration() {
        runCatching { vibrator.cancel() }
    }

    @Suppress("DEPRECATION")
    internal fun vibrateSafetyCheck() {
        if (machine.state.phase == SosPhase.COUNTDOWN || ReceiptAlarmStore.active.value != null) return
        runCatching {
            val amplitude = if (vibrator.hasAmplitudeControl()) 255 else VibrationEffect.DEFAULT_AMPLITUDE
            vibrator.vibrate(VibrationEffect.createOneShot(850, amplitude), alarmAttributes)
        }
    }

    internal fun playSafetyReceipt(reason: String) {
        if (machine.state.phase == SosPhase.COUNTDOWN) {
            pendingSafetyReceipt = true
            pendingReceiptReason = reason
        } else playReceiptSound(reason)
    }

    private fun flushSafetyReceipt() {
        if (!pendingSafetyReceipt) return
        pendingSafetyReceipt = false
        playReceiptSound(pendingReceiptReason)
    }

    fun close() {
        ReceiptAlarmStore.onSilence = null
        ReceiptAlarmStore.clear()
        pendingSafetyReceipt = false
        if (machine.state.phase == SosPhase.COUNTDOWN) cancel(machine.state.incidentId)
        if (machine.state.phase == SosPhase.SENDING) {
            machine.finish(machine.state.incidentId, SosPhase.UNKNOWN,
                "전송 중 감시가 종료되었습니다. 관리자 화면에서 접수 여부를 확인하세요.", SystemClock.elapsedRealtime())
            publish()
        }
        stopCountdownVibration()
        releaseAlarmPlayer()
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

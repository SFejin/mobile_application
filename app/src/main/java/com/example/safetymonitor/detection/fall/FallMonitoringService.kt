package com.example.safetymonitor.detection.fall

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.MainThread
import com.example.safetymonitor.BuildConfig
import com.example.safetymonitor.MainActivity
import com.example.safetymonitor.R
import com.example.safetymonitor.detection.DetectionStatus
import com.example.safetymonitor.sos.SosController
import com.example.safetymonitor.sos.SosSession
import com.example.safetymonitor.sos.SosStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Continues accelerometer + gyroscope monitoring independently of the Activity. */
class FallMonitoringService : Service() {
    private lateinit var detector: FallDetector
    private lateinit var sos: SosController
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false
    private var monitoringSession: SosSession? = null
    private var shuttingDown = false
    private val handler = Handler(Looper.getMainLooper())
    private val checkSession = object : Runnable {
        override fun run() {
            val session = monitoringSession
            val current = SosStore.session.value
            if (foregroundStartQueued || SosStore.state.value.active || (current === session && session?.valid() == true)) {
                handler.postDelayed(this, 1_000)
            } else if (current == null || current === session) {
                stop(this@FallMonitoringService)
            } else stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        sos = SosController(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "안전 센서 측정", NotificationManager.IMPORTANCE_LOW)
        )
        detector = FallDetector(this).apply {
            setMotionListener(FallMonitoringStore::updateMotion)
            setEventListener { event ->
                val alert = event.status == DetectionStatus.ALERT
                FallMonitoringStore.updateStatus(event.message, alert)
                if (alert && started && !shuttingDown && monitoringSession === SosStore.session.value) sos.detect()
                val manager = getSystemService(NotificationManager::class.java)
                if (started && !shuttingDown && manager.areNotificationsEnabled()) {
                    manager.notify(NOTIFICATION_ID, notification(event.message, alert))
                }
            }
        }
    }

    @SuppressLint("WakelockTimeout") // Held only for the logged-in monitoring session; released in onDestroy.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = SosStore.session.value
        val canMonitor = isEnabled(this) && session?.valid() == true
        // Even a queued start rejected for missing/expired auth must acknowledge Android first.
        if (!started) {
            val notification = notification(
                if (canMonitor) "가속도·자이로 센서로 낙상을 감지합니다."
                else "유효한 로그인이 없어 측정을 종료합니다."
            )
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (_: RuntimeException) {
                foregroundStartQueued = false
                return rejectStart(startId, session)
            }
        }
        foregroundStartQueued = false
        if (intent?.action == SosController.ACTION_CANCEL) {
            sos.cancel(intent.getStringExtra(SosController.EXTRA_ID).orEmpty())
        }
        if (!canMonitor || session?.valid() != true || SosStore.session.value !== session) {
            // Expiry/revocation cannot tear down an incident before its terminal transition.
            if (started && SosStore.state.value.active) return START_NOT_STICKY
            return rejectStart(startId, session)
        }
        if (monitoringSession !== session) {
            started = false
            releaseSensors()
            val ready = try {
                detector.start()
                val motion = FallMonitoringStore.state.value.motion
                if (motion.accelerometerActive && motion.gyroscopeActive) {
                    wakeLock = getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:FallMonitoring")
                        .also { it.acquire() }
                    true
                } else false
            } catch (_: RuntimeException) {
                false
            }
            if (!ready) {
                releaseSensors()
                return rejectStart(startId, session)
            }
            monitoringSession = session
            started = true
            handler.removeCallbacks(checkSession)
            handler.postDelayed(checkSession, 1_000)
        }
        completeStart(session, true)
        if (intent?.action == SosController.ACTION_TEST && BuildConfig.DEBUG) sos.detect(test = true)
        return START_STICKY
    }

    private fun rejectStart(startId: Int, session: SosSession?): Int {
        setEnabled(this, false)
        completeStart(session, false)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun releaseSensors() {
        detector.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        shuttingDown = true
        handler.removeCallbacks(checkSession)
        sos.close()
        releaseSensors()
        stopForeground(STOP_FOREGROUND_REMOVE)
        FallMonitoringStore.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(message: String, alert: Boolean = false): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_monitoring)
            .setContentTitle(if (alert) "낙상이 감지되었습니다" else "안전 센서 측정 중")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "fall_monitoring"
        private const val NOTIFICATION_ID = 1001
        private const val PREFERENCES = "fall_monitoring_session"
        private const val ENABLED = "enabled"

        private class StartRequest(val session: SosSession) {
            val result = CompletableDeferred<Boolean>()
        }

        private var pendingStart: StartRequest? = null
        private var foregroundStartQueued = false

        private fun completeStart(session: SosSession?, started: Boolean) {
            pendingStart?.takeIf { it.session === session }?.result?.complete(started)
        }

        private fun setEnabled(context: Context, enabled: Boolean): Boolean =
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).commit()

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(ENABLED, false)

        /** Wait for foreground promotion and sensor startup, not just a queued intent. */
        @MainThread
        suspend fun start(context: Context): Boolean {
            val session = SosStore.session.value?.takeIf { it.valid() } ?: return false
            pendingStart?.takeIf { it.session === session }?.let { return it.result.await() }
            val request = StartRequest(session)
            pendingStart = request
            var started = false
            try {
                if (!setEnabled(context, true)) return false
                try {
                    context.startForegroundService(Intent(context, FallMonitoringService::class.java))
                    foregroundStartQueued = true
                } catch (_: RuntimeException) {
                    return false
                }
                started = withTimeoutOrNull(5_000) { request.result.await() } == true
                return started
            } finally {
                if (pendingStart === request) {
                    pendingStart = null
                    if (!started && SosStore.session.value === session) setEnabled(context, false)
                }
                request.result.complete(false)
            }
        }

        @MainThread
        fun stop(context: Context) {
            setEnabled(context, false)
            val request = pendingStart
            // An undelivered foreground start still needs onStartCommand to acknowledge it.
            if (!foregroundStartQueued) context.stopService(Intent(context, FallMonitoringService::class.java))
            request?.result?.complete(false)
            FallMonitoringStore.clear()
        }
    }
}

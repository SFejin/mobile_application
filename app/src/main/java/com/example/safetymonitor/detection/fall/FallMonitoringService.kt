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
import android.os.IBinder
import android.os.PowerManager
import com.example.safetymonitor.MainActivity
import com.example.safetymonitor.R
import com.example.safetymonitor.detection.DetectionStatus
import com.example.safetymonitor.sos.SosController
import android.content.pm.ApplicationInfo

/** Continues accelerometer + gyroscope monitoring independently of the Activity. */
class FallMonitoringService : Service() {
    private lateinit var detector: FallDetector
    private lateinit var sos: SosController
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false
    private var shuttingDown = false

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
                if (alert && started && !shuttingDown) sos.detect()
                val manager = getSystemService(NotificationManager::class.java)
                if (started && !shuttingDown && manager.areNotificationsEnabled()) {
                    manager.notify(NOTIFICATION_ID, notification(event.message, alert))
                }
            }
        }
    }

    @SuppressLint("WakelockTimeout") // Held only for the logged-in monitoring session; released in onDestroy.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A queued start must not restart sensing after logout.
        if (!isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            val notification = notification("가속도·자이로 센서로 낙상을 감지합니다.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            started = true
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:FallMonitoring")
                .apply { acquire() }
            detector.start()
        }
        if (intent?.action == SosController.ACTION_CANCEL) {
            sos.cancel(intent.getStringExtra(SosController.EXTRA_ID).orEmpty())
        }
        if (intent?.action == SosController.ACTION_TEST &&
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) sos.detect(test = true)
        return START_STICKY
    }

    override fun onDestroy() {
        shuttingDown = true
        sos.close()
        detector.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(ENABLED, false)

        /** Called only from a visible Activity/login, where foreground starts are allowed. */
        fun start(context: Context): Boolean {
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(ENABLED, true).commit()
            return try {
                context.startForegroundService(Intent(context, FallMonitoringService::class.java))
                true
            } catch (error: RuntimeException) {
                context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(ENABLED, false).commit()
                FallMonitoringStore.clear()
                FallMonitoringStore.updateStatus("센서 측정을 시작할 수 없습니다. 다시 로그인해주세요.")
                false
            }
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(ENABLED, false).commit()
            context.stopService(Intent(context, FallMonitoringService::class.java))
            FallMonitoringStore.clear()
        }
    }
}

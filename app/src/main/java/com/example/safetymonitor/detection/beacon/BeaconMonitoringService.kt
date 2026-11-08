package com.example.safetymonitor.detection.beacon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.example.safetymonitor.MainActivity
import com.example.safetymonitor.R

/**
 * Foreground service that owns the BLE radio for exactly one role at a time (receiver XOR transmitter).
 *
 * Lifecycle rules:
 * - Started only from the visible app through [startReceiver] / [startTransmitter]; never sticky,
 *   never restored from disk, no boot receiver. A fresh process is always IDLE.
 * - Every command gets a process-wide generation number. A callback, timer or command carrying an
 *   older generation than the newest command is dropped, so a late callback can never reactivate a
 *   stopped role or overwrite a newer command.
 * - No automatic retry loops. The only automatic resume is event-driven: if Bluetooth / location is
 *   switched back on while the session is BLOCKED for that reason, the radio is started once.
 * - Everything is mutated on the main thread.
 */
class BeaconMonitoringService : Service() {
    private class Session(val generation: Long, val role: BeaconRole, val config: BeaconConfig?) {
        /** Bumped on every radio teardown so callbacks of a previous radio start become stale. */
        var epoch = 0
        var radioRunning = false
        /** True once the radio was actually running in this session, i.e. we are recovering, not launching. */
        var hasRun = false
        var waitingIssue: BeaconIssue = BeaconIssue.NONE
        var detector: BeaconDetector? = null
        var advertiser: BeaconAdvertiser? = null
        var lastNotificationKey: String? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var foregroundStarted = false
    private var lastStartId = 0
    private var environmentReceiverRegistered = false

    private val environmentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onEnvironmentChanged()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = session ?: return
            if (!isCurrent(s) || !s.radioRunning) return
            if (s.role == BeaconRole.RECEIVER) publishReceiver(s)
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "BLE 비콘", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_STOP -> userStop()
            ACTION_START_RECEIVER -> handleStart(BeaconRole.RECEIVER, null, intent.getLongExtra(EXTRA_GENERATION, -1L))
            ACTION_START_TRANSMITTER -> {
                val config = BeaconConfig(
                    id = intent.getIntExtra(EXTRA_ID, 0),
                    xCm = intent.getIntExtra(EXTRA_X_CM, 0),
                    yCm = intent.getIntExtra(EXTRA_Y_CM, 0),
                    referenceRssi = intent.getIntExtra(EXTRA_REFERENCE_RSSI, 0)
                )
                handleStart(BeaconRole.TRANSMITTER, config, intent.getLongExtra(EXTRA_GENERATION, -1L))
            }
            else -> if (session == null) finishService()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val s = session
        session = null
        if (s != null) {
            teardownRadio(s)
            // Destroyed without our own stop/failure path (e.g. killed by the system): say so truthfully.
            publishIfCurrent(s.generation, stoppedSnapshot("BLE 서비스가 종료되어 중지되었습니다"))
        }
        unregisterEnvironmentReceiver()
        if (foregroundStarted) {
            foregroundStarted = false
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: RuntimeException) {
            }
        }
        super.onDestroy()
    }

    // ---- commands -------------------------------------------------------------------------------

    private fun handleStart(role: BeaconRole, config: BeaconConfig?, generation: Long) {
        if (!isCurrentGeneration(generation)) {
            // Superseded before it ran. We were started with startForegroundService(), so we still owe
            // a startForeground() call; then follow whatever the newest command is.
            if (!foregroundStarted) enterForeground(role, "BLE 준비 중", "이전 요청을 정리하는 중입니다")
            if (isLatestStop()) finishService()
            return
        }
        session?.let { teardownRadio(it) } // exclusive roles: the previous radio is fully down first
        val s = Session(generation, role, config)
        session = s

        val title = if (role == BeaconRole.RECEIVER) "BLE 비콘 수신" else "BLE 비콘 송신"
        if (!enterForeground(role, title, "시작하는 중입니다")) {
            fail(s, BeaconFailure(BeaconIssue.START_FAILED, "백그라운드 실행(포그라운드 서비스)을 시작하지 못했습니다"))
            return
        }
        if (role == BeaconRole.TRANSMITTER && (config == null || !BeaconProtocol.isValid(config))) {
            fail(s, BeaconFailure(BeaconIssue.START_FAILED, "비콘 설정 값이 올바르지 않습니다"))
            return
        }
        publish(s, startingSnapshot(role, config))
        registerEnvironmentReceiver()
        beginRadio(s)
    }

    private fun userStop() {
        val generation = markStop()
        session?.let { teardownRadio(it) }
        publishIfCurrent(generation, stoppedSnapshot("BLE 중지됨"))
        session = null
        finishService()
    }

    // ---- radio ----------------------------------------------------------------------------------

    private fun beginRadio(s: Session) {
        if (!isCurrent(s)) return
        val issue = BeaconPermissions.issue(this, s.role)
        if (issue != BeaconIssue.NONE) {
            block(s, issue)
            return
        }
        s.waitingIssue = BeaconIssue.NONE
        val epoch = s.epoch
        when (s.role) {
            BeaconRole.RECEIVER -> {
                val detector = BeaconDetector(this)
                s.detector = detector
                val failure = detector.start { asyncFailure -> handler.post { if (isCurrent(s, epoch)) fail(s, asyncFailure) } }
                if (failure != null) {
                    s.detector = null
                    handleSyncFailure(s, failure)
                    return
                }
                s.radioRunning = true
                s.hasRun = true
                publishReceiver(s)
            }
            BeaconRole.TRANSMITTER -> {
                val config = s.config ?: return
                val advertiser = BeaconAdvertiser(this)
                s.advertiser = advertiser
                val failure = advertiser.start(
                    config,
                    onStarted = { handler.post { if (isCurrent(s, epoch)) publishTransmitterActive(s, config) } },
                    onFailed = { asyncFailure -> handler.post { if (isCurrent(s, epoch)) fail(s, asyncFailure) } }
                )
                if (failure != null) {
                    s.advertiser = null
                    handleSyncFailure(s, failure)
                    return
                }
                s.radioRunning = true
                s.hasRun = true // requested; reported ACTIVE only from onStartSuccess
            }
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MILLIS)
    }

    private fun handleSyncFailure(s: Session, failure: BeaconFailure) {
        when (failure.issue) {
            BeaconIssue.BLUETOOTH_OFF, BeaconIssue.LOCATION_OFF -> block(s, failure.issue)
            BeaconIssue.PERMISSIONS, BeaconIssue.UNSUPPORTED -> block(s, failure.issue, failure.message)
            else -> fail(s, failure)
        }
    }

    /** Releases radio, scan/advertise callbacks and the periodic timer. Idempotent. */
    private fun teardownRadio(s: Session) {
        s.epoch++
        s.radioRunning = false
        handler.removeCallbacks(tick)
        s.detector?.stop()
        s.detector = null
        s.advertiser?.stop()
        s.advertiser = null
    }

    /**
     * Prerequisite missing. For a session that was already running, Bluetooth/location off is recoverable:
     * it waits for the matching system broadcast. Otherwise (initial launch, missing permission,
     * unsupported hardware) the service ends and the BLOCKED state stays visible for the UI's user action.
     */
    private fun block(s: Session, issue: BeaconIssue, message: String = issueMessage(issue, s.role)) {
        teardownRadio(s)
        val text = if (message == issueMessage(issue, s.role) && !s.hasRun) initialIssueMessage(issue, s.role) ?: message else message
        publish(s, blockedSnapshot(s.role, issue, text, s.config))
        // Only a session that was already running waits for the OS to turn the radio back on. A launch that
        // never got its radio running ends here (no ineligible foreground service lingers, no silent auto-start).
        if (s.hasRun && (issue == BeaconIssue.BLUETOOTH_OFF || issue == BeaconIssue.LOCATION_OFF)) {
            s.waitingIssue = issue
        } else {
            session = null
            finishService()
        }
    }

    /** Unrecoverable radio error: show it, free everything, do not retry. */
    private fun fail(s: Session, failure: BeaconFailure) {
        teardownRadio(s)
        val snapshot = BeaconSnapshot(
            role = s.role,
            status = BeaconStatus.ERROR,
            issue = failure.issue,
            message = failure.message,
            position = BeaconPosition.Unavailable("BLE 오류로 위치를 계산할 수 없습니다"),
            transmitterConfig = s.config
        )
        publishIfCurrent(s.generation, snapshot)
        if (session === s) session = null
        finishService()
    }

    private fun onEnvironmentChanged() {
        val s = session ?: return
        if (!isCurrent(s)) return
        val issue = BeaconPermissions.issue(this, s.role)
        // Right after Bluetooth turns on the scanner/advertiser can briefly be null: not a real "unsupported".
        if (issue == BeaconIssue.UNSUPPORTED && s.waitingIssue != BeaconIssue.NONE) {
            // Bluetooth is on but its scanner/advertiser is unavailable. Not proven "unsupported" hardware and
            // not worth a retry loop: say so, end the session, and let the user retry explicitly.
            fail(s, BeaconFailure(BeaconIssue.START_FAILED, "Bluetooth를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시작해주세요"))
            return
        }
        when {
            issue == BeaconIssue.NONE && !s.radioRunning && s.waitingIssue != BeaconIssue.NONE -> beginRadio(s) // single attempt
            issue != BeaconIssue.NONE && (s.radioRunning || s.waitingIssue != issue) -> block(s, issue)
        }
    }

    // ---- publishing -----------------------------------------------------------------------------

    private fun publishReceiver(s: Session) {
        val detector = s.detector ?: return
        val now = SystemClock.elapsedRealtime()
        val estimate = detector.snapshot(now)
        val observations = estimate.observations.sortedBy { it.config.id }
        val message = if (observations.isEmpty()) "BLE 수신 중 · 비콘 신호 대기" else "BLE 수신 중 · 비콘 ${observations.size}개 감지"
        publish(
            s,
            BeaconSnapshot(
                role = BeaconRole.RECEIVER,
                status = BeaconStatus.ACTIVE,
                issue = BeaconIssue.NONE,
                message = message,
                observations = observations,
                position = estimate.position
            )
        )
    }

    private fun publishTransmitterActive(s: Session, config: BeaconConfig) {
        publish(
            s,
            BeaconSnapshot(
                role = BeaconRole.TRANSMITTER,
                status = BeaconStatus.ACTIVE,
                message = "비콘 송신 중 (ID ${config.id})",
                position = BeaconPosition.Unavailable("송신 모드에서는 위치를 계산하지 않습니다"),
                transmitterConfig = config
            )
        )
    }

    private fun publish(s: Session, snapshot: BeaconSnapshot) {
        if (!publishIfCurrent(s.generation, snapshot)) return
        val key = "${snapshot.status}/${snapshot.issue}/${snapshot.role}"
        if (s.lastNotificationKey == key) return
        s.lastNotificationKey = key
        if (!foregroundStarted) return
        val title = if (snapshot.role == BeaconRole.RECEIVER) "BLE 비콘 수신" else "BLE 비콘 송신"
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title, snapshot.message))
        } catch (_: RuntimeException) {
        }
    }

    // ---- foreground / notification --------------------------------------------------------------

    private fun enterForeground(role: BeaconRole, title: String, text: String): Boolean {
        val notification = buildNotification(title, text)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Receiver: location (scan results need it) + connectedDevice. Transmitter: connectedDevice only.
                val type = if (role == BeaconRole.RECEIVER) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            true
        } catch (_: RuntimeException) {
            // SecurityException (missing FGS-type prerequisite) or ForegroundServiceStartNotAllowedException.
            false
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BeaconMonitoringService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_monitoring)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_monitoring), "중지", stop).build()
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** Ends only this service. Uses the latest start id so a newer queued command is never lost. */
    private fun finishService() {
        handler.removeCallbacks(tick)
        if (foregroundStarted) {
            foregroundStarted = false
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: RuntimeException) {
            }
        }
        stopSelf(lastStartId)
    }

    private fun registerEnvironmentReceiver() {
        if (environmentReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction("android.location.MODE_CHANGED")
        }
        // Every action is a protected system broadcast, so third parties cannot forge it. EXPORTED is
        // required because ACTION_STATE_CHANGED is sent by the Bluetooth process (non-system UID), which a
        // NOT_EXPORTED receiver would never hear. The handler only re-reads OS state, never trusts extras.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(environmentReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(environmentReceiver, filter)
        }
        environmentReceiverRegistered = true
    }

    private fun unregisterEnvironmentReceiver() {
        if (!environmentReceiverRegistered) return
        environmentReceiverRegistered = false
        try {
            unregisterReceiver(environmentReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun isCurrent(s: Session, epoch: Int = s.epoch): Boolean =
        session === s && s.epoch == epoch && isCurrentGeneration(s.generation)

    companion object {
        private const val CHANNEL_ID = "beacon_monitoring"
        private const val NOTIFICATION_ID = 1002
        private const val TICK_MILLIS = 1000L

        private const val ACTION_START_RECEIVER = "com.example.safetymonitor.beacon.START_RECEIVER"
        private const val ACTION_START_TRANSMITTER = "com.example.safetymonitor.beacon.START_TRANSMITTER"
        private const val ACTION_STOP = "com.example.safetymonitor.beacon.STOP"
        private const val EXTRA_GENERATION = "generation"
        private const val EXTRA_ID = "id"
        private const val EXTRA_X_CM = "x_cm"
        private const val EXTRA_Y_CM = "y_cm"
        private const val EXTRA_REFERENCE_RSSI = "reference_rssi"

        private val lock = Any()
        private var latestGeneration = 0L
        private var latestIsStop = false

        private fun isCurrentGeneration(generation: Long): Boolean = synchronized(lock) { generation == latestGeneration }

        private fun isLatestStop(): Boolean = synchronized(lock) { latestIsStop }

        /** Compare-and-publish: only the newest command's session may write the store. */
        private fun publishIfCurrent(generation: Long, snapshot: BeaconSnapshot): Boolean = synchronized(lock) {
            if (generation != latestGeneration) return@synchronized false
            BeaconMonitoringStore.publish(snapshot)
            true
        }

        private fun markStop(): Long = synchronized(lock) {
            latestGeneration += 1
            latestIsStop = true
            latestGeneration
        }

        /** Starts the receiver. A repeated call while it is already STARTING/ACTIVE (e.g. Activity recreation) is a no-op. */
        fun startReceiver(context: Context) {
            val app = context.applicationContext
            val generation = synchronized(lock) {
                val current = BeaconMonitoringStore.state.value
                if (current.role == BeaconRole.RECEIVER &&
                    (current.status == BeaconStatus.STARTING || current.status == BeaconStatus.ACTIVE)
                ) {
                    return
                }
                latestIsStop = false
                ++latestGeneration
            }
            launch(app, BeaconRole.RECEIVER, null, generation)
        }

        fun startTransmitter(context: Context, config: BeaconConfig) {
            val app = context.applicationContext
            val generation = synchronized(lock) {
                val current = BeaconMonitoringStore.state.value
                if (current.role == BeaconRole.TRANSMITTER && current.transmitterConfig == config &&
                    (current.status == BeaconStatus.STARTING || current.status == BeaconStatus.ACTIVE)
                ) {
                    return
                }
                latestIsStop = false
                ++latestGeneration
            }
            launch(app, BeaconRole.TRANSMITTER, config, generation)
        }

        /** Stops BLE only (fall monitoring is untouched), clears observations, and reports STOPPED. */
        fun stop(context: Context) {
            val app = context.applicationContext
            val generation = markStop()
            publishIfCurrent(generation, stoppedSnapshot("BLE 중지됨"))
            app.stopService(Intent(app, BeaconMonitoringService::class.java))
        }

        private fun launch(app: Context, role: BeaconRole, config: BeaconConfig?, generation: Long) {
            val serviceIntent = Intent(app, BeaconMonitoringService::class.java)
            if (role == BeaconRole.TRANSMITTER && (config == null || !BeaconProtocol.isValid(config))) {
                app.stopService(serviceIntent)
                publishIfCurrent(
                    generation,
                    BeaconSnapshot(
                        role = role,
                        status = BeaconStatus.ERROR,
                        issue = BeaconIssue.START_FAILED,
                        message = "비콘 설정 값이 올바르지 않습니다",
                        position = BeaconPosition.Unavailable("송신 모드에서는 위치를 계산하지 않습니다"),
                        transmitterConfig = config
                    )
                )
                return
            }
            val issue = BeaconPermissions.issue(app, role)
            if (issue != BeaconIssue.NONE) {
                // Exclusive roles: whatever was running (e.g. the transmitter) must stop even though the new role is blocked.
                app.stopService(serviceIntent)
                val message = initialIssueMessage(issue, role) ?: issueMessage(issue, role)
                publishIfCurrent(generation, blockedSnapshot(role, issue, message, config))
                return
            }
            publishIfCurrent(generation, startingSnapshot(role, config))
            serviceIntent.putExtra(EXTRA_GENERATION, generation)
            if (role == BeaconRole.RECEIVER) {
                serviceIntent.action = ACTION_START_RECEIVER
            } else if (config != null) {
                serviceIntent.action = ACTION_START_TRANSMITTER
                serviceIntent.putExtra(EXTRA_ID, config.id)
                    .putExtra(EXTRA_X_CM, config.xCm)
                    .putExtra(EXTRA_Y_CM, config.yCm)
                    .putExtra(EXTRA_REFERENCE_RSSI, config.referenceRssi)
            }
            try {
                app.startForegroundService(serviceIntent)
            } catch (_: RuntimeException) {
                // e.g. ForegroundServiceStartNotAllowedException when the app is not visible.
                publishIfCurrent(
                    generation,
                    BeaconSnapshot(
                        role = role,
                        status = BeaconStatus.ERROR,
                        issue = BeaconIssue.START_FAILED,
                        message = "BLE 백그라운드 서비스를 시작하지 못했습니다. 앱을 열어둔 상태에서 다시 시도하세요",
                        transmitterConfig = config
                    )
                )
            }
        }

        private fun startingSnapshot(role: BeaconRole, config: BeaconConfig?) = BeaconSnapshot(
            role = role,
            status = BeaconStatus.STARTING,
            message = if (role == BeaconRole.RECEIVER) "BLE 수신 시작 중" else "BLE 송신 시작 중",
            position = BeaconPosition.Unavailable(
                if (role == BeaconRole.RECEIVER) "유효한 비콘 신호 대기 중" else "송신 모드에서는 위치를 계산하지 않습니다"
            ),
            transmitterConfig = if (role == BeaconRole.TRANSMITTER) config else null
        )

        private fun blockedSnapshot(role: BeaconRole, issue: BeaconIssue, message: String, config: BeaconConfig?) =
            BeaconSnapshot(
                role = role,
                status = BeaconStatus.BLOCKED,
                issue = issue,
                message = message,
                position = BeaconPosition.Unavailable(
                    if (role == BeaconRole.RECEIVER) "BLE 수신이 중단되어 위치를 계산할 수 없습니다" else "송신 모드에서는 위치를 계산하지 않습니다"
                ),
                transmitterConfig = if (role == BeaconRole.TRANSMITTER) config else null
            )

        private fun stoppedSnapshot(message: String) = BeaconSnapshot(
            role = BeaconRole.RECEIVER,
            status = BeaconStatus.STOPPED,
            issue = BeaconIssue.NONE,
            message = message
        )

        /** Wording for a session that never got its radio running: it does not resume by itself. */
        private fun initialIssueMessage(issue: BeaconIssue, role: BeaconRole): String? = when (issue) {
            BeaconIssue.BLUETOOTH_OFF -> "Bluetooth가 꺼져 있습니다. 켠 뒤 다시 시작해주세요"
            BeaconIssue.LOCATION_OFF ->
                if (role == BeaconRole.RECEIVER) "위치 서비스가 꺼져 있어 수신을 시작하지 못했습니다. 켠 뒤 다시 시작해주세요" else null
            else -> null
        }

        private fun issueMessage(issue: BeaconIssue, role: BeaconRole): String = when (issue) {
            BeaconIssue.PERMISSIONS ->
                if (role == BeaconRole.RECEIVER) "BLE 수신에 필요한 권한(주변 기기·정확한 위치)이 허용되지 않았습니다"
                else "BLE 송신에 필요한 권한(주변 기기)이 허용되지 않았습니다"
            BeaconIssue.BLUETOOTH_OFF -> "Bluetooth가 꺼져 있습니다. 켜면 자동으로 이어집니다"
            BeaconIssue.LOCATION_OFF -> "위치 서비스가 꺼져 있어 BLE 신호를 수신할 수 없습니다. 켜면 자동으로 이어집니다"
            BeaconIssue.UNSUPPORTED ->
                if (role == BeaconRole.RECEIVER) "이 기기는 BLE 수신을 지원하지 않습니다"
                else "이 기기는 BLE 송신(광고)을 지원하지 않습니다"
            BeaconIssue.SCAN_FAILED -> "BLE 스캔에 실패했습니다"
            BeaconIssue.ADVERTISE_FAILED -> "BLE 송신에 실패했습니다"
            BeaconIssue.START_FAILED -> "BLE를 시작하지 못했습니다"
            BeaconIssue.NONE -> ""
        }
    }
}

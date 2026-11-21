package com.example.safetymonitor

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import androidx.lifecycle.ViewModelProvider
import com.example.safetymonitor.detection.fall.FallMonitoringService
import com.example.safetymonitor.detection.fall.FallMonitoringStore
import com.example.safetymonitor.ui.WorkSafePage
import com.example.safetymonitor.ui.WorkSafeViewModel
import kotlinx.coroutines.runBlocking

/** Device integration checks, without an additional test-library dependency. */
class BackgroundMonitoringTestRunner : Instrumentation() {
    private var checks: String? = null
    private var cleanupIncident: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        checks = arguments?.getString("checks")
        cleanupIncident = arguments?.getString("cleanupIncident")
        start()
    }

    override fun onStart() {
        val results = StringBuilder()
        var resultCode = Activity.RESULT_OK
        var activity: Activity? = null
        var model: WorkSafeViewModel? = null
        val prefs = targetContext.getSharedPreferences("worksafe_theme", 0)
        val originalServer = prefs.getString("sos_server", null)
        var originalIncident: com.example.safetymonitor.sos.SosState? = null
        fun await(label: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 8_000L
            while (!condition()) {
                check(System.currentTimeMillis() < deadline) { "Timed out: $label" }
                Thread.sleep(50)
            }
            results.appendLine("PASS: $label")
        }
        fun openApp(): Activity = startActivitySync(
            Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        fun shell(command: String) {
            uiAutomation.executeShellCommand(command).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
        }
        fun getModel(activity: Activity): WorkSafeViewModel {
            var value: WorkSafeViewModel? = null
            runOnMainSync {
                value = ViewModelProvider(activity as MainActivity)[WorkSafeViewModel::class.java]
            }
            return checkNotNull(value)
        }
        fun serviceExists(): Boolean = targetContext.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE).any { it.service.className == FallMonitoringService::class.java.name }
        try {
            if (checks == "accounts" || checks == "monitoring-auth" || checks == "watch-link") {
                when (checks) {
                    "accounts" -> AccountIntegrationChecks.run(this, results)
                    "monitoring-auth" -> MonitoringAuthChecks.run(this, results)
                    "watch-link" -> WatchLinkChecks.run(this, results)
                }
                return
            }
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            activity = openApp()
            model = getModel(activity)
            originalIncident = com.example.safetymonitor.sos.SosStore.state.value.let {
                if (it.test && it.incidentId == cleanupIncident) com.example.safetymonitor.sos.SosState() else it
            }
            runBlocking { checkNotNull(model).logout() }
            await("logout leaves no running service") { !serviceExists() }
            AccountIntegrationChecks.loginFixture(checkNotNull(model))
            await("authenticated session starts both real sensor streams") {
                val reading = FallMonitoringStore.state.value.motion
                reading.acceleration != null && reading.gyroscope != null
            }
            SosIntegrationChecks.run(this, checkNotNull(model), results)
            if (checks == "sos") {
                return
            }
            if (targetContext.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                await("ongoing monitoring notification is present") {
                    targetContext.getSystemService(NotificationManager::class.java)
                        .activeNotifications.any { it.id == 1001 }
                }
            } else {
                results.appendLine("PASS: monitoring started with notification permission denied")
            }
            shell("input keyevent 3")
            Thread.sleep(300)
            var previous = FallMonitoringStore.state.value.sampledAtMillis
            await("sensor updates continue on the launcher") {
                FallMonitoringStore.state.value.sampledAtMillis > previous + 500
            }
            shell("input keyevent 223")
            await("screen is off") { !targetContext.getSystemService(PowerManager::class.java).isInteractive }
            previous = FallMonitoringStore.state.value.sampledAtMillis
            await("sensor updates continue with screen off") {
                FallMonitoringStore.state.value.sampledAtMillis > previous + 1_000
            }
            runOnMainSync { checkNotNull(activity).finishAndRemoveTask() }
            previous = FallMonitoringStore.state.value.sampledAtMillis
            await("removing the Activity task does not stop measurement") {
                FallMonitoringStore.state.value.sampledAtMillis > previous + 500
            }
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            activity = openApp()
            model = getModel(activity)
            await("reopened screen retains the logged-in session and live readings") {
                model.page == WorkSafePage.HOME && model.motionReading.acceleration != null
            }
            runBlocking { model.logout() }
            await("logout stops service and clears sensor readings") {
                !serviceExists() && !FallMonitoringStore.state.value.motion.monitoring &&
                    FallMonitoringStore.state.value.motion.acceleration == null
            }
            await("logout removes the ongoing notification") {
                targetContext.getSystemService(NotificationManager::class.java)
                    .activeNotifications.none { it.id == 1001 }
            }
            check(!FallMonitoringService.isEnabled(targetContext))
            results.appendLine("PASS: logout disables sticky restart")
        } catch (error: Throwable) {
            results.appendLine(error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            originalIncident?.let { state ->
                runOnMainSync { com.example.safetymonitor.sos.SosStore.publish(targetContext, state) }
            }
            runBlocking { model?.logout() }
            runOnMainSync { activity?.finish() }
            prefs.edit().apply { if (originalServer == null) remove("sos_server") else putString("sos_server", originalServer) }.commit()
            finish(resultCode, Bundle().apply { putString("stream", results.toString()) })
        }
    }
}

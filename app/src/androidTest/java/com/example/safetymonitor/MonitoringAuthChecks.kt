package com.example.safetymonitor

import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Intent
import com.example.safetymonitor.detection.fall.FallMonitoringService
import com.example.safetymonitor.detection.fall.FallMonitoringStore
import com.example.safetymonitor.sos.SosSession
import com.example.safetymonitor.sos.SosStore
import java.time.Instant

object MonitoringAuthChecks {
    fun run(instrumentation: Instrumentation, results: StringBuilder) {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("fall_monitoring_session", 0)
        val hadEnabled = prefs.contains("enabled")
        val originalEnabled = prefs.getBoolean("enabled", false)
        fun serviceExists() = context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE).any { it.service.className == FallMonitoringService::class.java.name }
        fun await(label: String, condition: () -> Boolean) {
            val end = System.currentTimeMillis() + 8_000
            while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out: $label" }; Thread.sleep(50) }
            results.appendLine("PASS: $label")
        }
        check(!originalEnabled && !serviceExists() && SosStore.session.value == null) {
            "Refusing to disturb an existing monitoring session"
        }
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            fun startWith(session: SosSession?) {
                instrumentation.runOnMainSync {
                    SosStore.connect(session)
                    check(prefs.edit().putBoolean("enabled", true).commit())
                    check(FallMonitoringService.isEnabled(context))
                    context.startForegroundService(Intent(context, FallMonitoringService::class.java))
                }
            }
            fun rejected(label: String) {
                await(label) {
                    !FallMonitoringService.isEnabled(context) && !serviceExists() &&
                        !FallMonitoringStore.state.value.motion.monitoring &&
                        FallMonitoringStore.state.value.motion.acceleration == null
                }
                // Keep instrumentation alive beyond the foreground-service promotion deadline.
                Thread.sleep(12_000)
                check(!FallMonitoringService.isEnabled(context) && !serviceExists() &&
                    !FallMonitoringStore.state.value.motion.monitoring)
            }
            startWith(null)
            rejected("enabled restart without a session stops and clears monitoring")
            startWith(SosSession("http://127.0.0.1:1", 77, "expired-synthetic", Instant.EPOCH))
            rejected("enabled restart with an expired session stops and clears monitoring")
            startWith(SosSession("http://127.0.0.1:1", 77, "valid-synthetic", Instant.parse("2099-01-01T00:00:00Z")))
            await("valid session starts the real service and sensor monitoring") {
                val motion = FallMonitoringStore.state.value.motion
                FallMonitoringService.isEnabled(context) && serviceExists() && motion.monitoring &&
                    motion.acceleration != null && motion.gyroscope != null
            }
            instrumentation.runOnMainSync { FallMonitoringService.stop(context); SosStore.connect(null) }
            await("normal stop clears the service and sensor monitoring") {
                !FallMonitoringService.isEnabled(context) && !serviceExists() &&
                    !FallMonitoringStore.state.value.motion.monitoring &&
                    FallMonitoringStore.state.value.motion.acceleration == null
            }
        } finally {
            instrumentation.runOnMainSync {
                FallMonitoringService.stop(context)
                SosStore.connect(null)
                prefs.edit().apply { if (hadEnabled) putBoolean("enabled", originalEnabled) else remove("enabled") }.commit()
                activity.finish()
            }
        }
    }
}

package com.example.safetymonitor.detection.fall

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FallMonitoringSnapshot(
    val motion: MotionReading = MotionReading(),
    val message: String = "센서 측정 대기 중",
    val alert: Boolean = false,
    val sampledAtMillis: Long = 0L
)

/** The service owns sensing; any recreated screen observes its current snapshot. */
object FallMonitoringStore {
    private val mutableState = MutableStateFlow(FallMonitoringSnapshot())
    val state = mutableState.asStateFlow()

    internal fun updateMotion(reading: MotionReading) {
        mutableState.value = mutableState.value.copy(
            motion = reading,
            sampledAtMillis = SystemClock.elapsedRealtime()
        )
    }

    internal fun updateStatus(message: String, alert: Boolean = false) {
        mutableState.value = mutableState.value.copy(message = message, alert = alert)
    }

    internal fun clear() {
        mutableState.value = FallMonitoringSnapshot(message = "센서 측정 중지")
    }
}

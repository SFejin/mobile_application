package com.example.safetymonitor.detection.fall

/** Immutable copies: SensorEvent.values is reused by Android after each callback. */
data class MotionVector(val x: Float, val y: Float, val z: Float, val magnitude: Float)

data class MotionReading(
    val acceleration: MotionVector? = null,
    val gyroscope: MotionVector? = null,
    val accelerometerActive: Boolean = false,
    val gyroscopeActive: Boolean = false,
    val monitoring: Boolean = false
)

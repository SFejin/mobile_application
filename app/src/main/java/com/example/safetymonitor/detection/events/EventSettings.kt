package com.example.safetymonitor.detection.events

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-owned policy. Legacy vital_alert_limits preferences are intentionally never read. */
object EventSettings {
    val fixedConfig = EventConfig(heart = VitalLimits(50.0, 120.0), skin = null)
    val state = MutableStateFlow(fixedConfig).asStateFlow()
}

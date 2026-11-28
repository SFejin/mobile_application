package com.example.safetymonitor.detection.events

import com.example.safetymonitor.watch.WatchSummary

/** Isolated synthetic windows; never overwrites watch data or production detector state. */
fun vitalTestEvent(skin: Boolean, now: Long): DetectedEvent {
    val rule = VitalRule(EventSettings.fixedConfig)
    var result = emptyList<DetectedEvent>()
    for (i in 0..7) {
        val end = now - (7 - i) * 300_000L
        val sample = WatchSummary(end - 300_000L, end, 5,
            if (!skin && i >= 6) 130.0 else 80.0,
            if (skin && i >= 6) 35.0 else 33.0, 25.0, 10, 10)
        result = rule.evaluate(sample, end)
    }
    return result.single().let { it.copy(sender = it.sender + " · 테스트", description = "[테스트] " + it.description) }
}

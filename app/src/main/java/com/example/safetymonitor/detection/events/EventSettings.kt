package com.example.safetymonitor.detection.events

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object EventSettings {
    private val mutable = MutableStateFlow(EventConfig())
    val state = mutable.asStateFlow()
    fun initialize(context: Context) {
        val p = context.getSharedPreferences("worker_event_rules", Context.MODE_PRIVATE)
        mutable.value = runCatching {
            EventConfig(p.getString("ids", "").orEmpty().split(',').filter { it.isNotBlank() }.map { it.toInt() }.toSet(),
                p.getBoolean("heart", true), p.getInt("low", 50), p.getInt("high", 120))
        }.getOrDefault(EventConfig())
    }
    fun save(context: Context, ids: String, heart: Boolean, low: String, high: String): String? {
        val config = try {
            val tokens = ids.trim().split(Regex("[,\\s]+" )).filter { it.isNotEmpty() }
            EventConfig(tokens.map { it.toInt() }.toSet(), heart, low.toInt(), high.toInt())
        } catch (_: IllegalArgumentException) {
            return "비콘 ID는 1~65535(최대 16개), 심박 하한은 35~100, 상한은 100~230이며 두 값은 20 이상 차이나야 합니다."
        }
        context.getSharedPreferences("worker_event_rules", Context.MODE_PRIVATE).edit()
            .putString("ids", config.dangerIds.sorted().joinToString(","))
            .putBoolean("heart", config.heartEnabled).putInt("low", config.lowHeartRate).putInt("high", config.highHeartRate).apply()
        mutable.value = config
        return null
    }
}

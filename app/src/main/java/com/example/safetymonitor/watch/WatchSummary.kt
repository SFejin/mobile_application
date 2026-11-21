package com.example.safetymonitor.watch

import com.example.workersafety.link.WatchLinkProtocol.Result

/** The watch sends interval averages for HR, but the LAST valid temperature. */
data class WatchSummary(
    val windowStart: Long,
    val timestamp: Long,
    val intervalMinutes: Int,
    val heartRate: Double?,
    val skinC: Double?,
    val ambientC: Double?,
    val heartCount: Int,
    val skinCount: Int
) {
    fun stale(now: Long): Boolean = now - timestamp > intervalMinutes * 60_000L + 120_000L

    companion object {
        const val PATH = "/workersafety/summary"
        const val CAPABILITY = "workersafety_watch"

        /** Strict types and validity flags prevent missing values becoming plausible zeroes. */
        fun parse(values: Map<String, Any?>, now: Long): WatchSummary? = try {
            fun long(key: String) = values[key] as Long
            fun int(key: String) = values[key] as Int
            fun bool(key: String) = values[key] as Boolean
            fun measurement(flag: String, key: String, range: ClosedFloatingPointRange<Double>): Double? {
                if (!bool(flag)) {
                    require(!values.containsKey(key))
                    return null
                }
                val value = values[key] as Double
                require(value.isFinite() && value in range)
                return value
            }
            val result = WatchSummary(long("window_start"), long("timestamp"), int("interval_minutes"),
                measurement("has_hr", "hr_avg", 35.0..230.0),
                measurement("has_skin", "skin_c", 10.0..50.0),
                measurement("has_ambient", "ambient_c", -30.0..70.0), int("hr_count"), int("skin_count"))
            require(result.windowStart > 0 && result.timestamp > result.windowStart)
            require(result.timestamp - 60_000L <= now)
            require(result.intervalMinutes == 5 || result.intervalMinutes == 10)
            require(result.heartCount >= 0 && result.skinCount >= 0)
            require((result.heartRate != null) == (result.heartCount > 0))
            require((result.skinC != null) == (result.skinCount > 0))
            require(result.ambientC == null || result.skinC != null)
            result
        } catch (_: RuntimeException) { null }
    }
}

/** Kept separate from Android for reconnect/account-boundary regression tests. */
class WatchReadingWindow(val signedInAt: Long) {
    var source: String? = null
        private set
    var latest: WatchSummary? = null
        private set

    private var latestId: String? = null
    private val usedIds = HashSet<String>()

    fun accept(node: String, summary: WatchSummary): Boolean =
        admit(node, summary, id = null) == Result.ACCEPTED

    internal fun admit(node: String, summary: WatchSummary, id: String?): Result {
        if (node.isBlank() || summary.windowStart <= 0 || summary.timestamp <= summary.windowStart) {
            return Result.INVALID_DATA
        }
        if (source != null && source != node) return Result.WATCH_MISMATCH
        if (id != null && id == latestId) {
            return if (summary == latest) Result.ACCEPTED else Result.INVALID_DATA
        }
        if (summary.windowStart < signedInAt || summary.windowStart < (latest?.timestamp ?: 0L)) {
            return Result.NEW_WINDOW_REQUIRED
        }
        if (id != null && id in usedIds) return Result.INVALID_DATA
        source = node
        latest = summary
        latestId = id
        if (id != null) usedIds.add(id)
        return Result.ACCEPTED
    }
}

package com.example.safetymonitor.network

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

enum class MonitoringStatus { RUNNING, STOPPED }
enum class HeartRateStatus { NORMAL, DANGER }

data class HeartAssessment(val status: HeartRateStatus, val measuredAt: Instant)

data class WorkerStatusRequest(
    val monitoringStatus: MonitoringStatus,
    val reportedAt: Instant,
    val watchLastSeenAt: Instant? = null,
    val heart: HeartAssessment? = null
) {
    internal fun toJson() = JSONObject().apply {
        put("monitoringStatus", monitoringStatus.name)
        put("reportedAt", reportedAt.toString())
        watchLastSeenAt?.let {
            put("devices", JSONArray().put(JSONObject().put("type", "워치").put("lastSeenAt", it.toString())))
        }
        heart?.let {
            put("heartRateStatus", it.status.name)
            put("measuredAt", it.measuredAt.toString())
        }
    }
}

data class WorkerStatusResult(
    val monitoringStatus: MonitoringStatus,
    val reportedAt: Instant,
    val lastSeenAt: Instant,
    val watchLastSeenAt: Instant?,
    val heart: HeartAssessment?,
    val heartRateDataStatus: String
) {
    companion object {
        internal fun fromJson(json: JSONObject): WorkerStatusResult {
            fun optionalTime(key: String) = if (json.isNull(key)) null else Instant.parse(json.getString(key))
            val measured = optionalTime("measuredAt")
            val status = if (json.isNull("heartRateStatus")) null else HeartRateStatus.valueOf(json.getString("heartRateStatus"))
            require((measured == null) == (status == null))
            return WorkerStatusResult(
                MonitoringStatus.valueOf(json.getString("monitoringStatus")),
                Instant.parse(json.getString("reportedAt")), Instant.parse(json.getString("lastSeenAt")),
                optionalTime("watchLastSeenAt"),
                if (status != null && measured != null) HeartAssessment(status, measured) else null,
                json.getString("heartRateDataStatus")
            )
        }
    }
}

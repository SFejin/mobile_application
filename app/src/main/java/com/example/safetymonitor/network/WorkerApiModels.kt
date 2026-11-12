package com.example.safetymonitor.network

import org.json.JSONObject
import java.time.Instant

/** Worker API models for the server contract pinned in the project documentation. */
data class WorkerLoginRequest(val workerId: Long, val password: String) {
    init {
        require(workerId > 0) { "workerId must be positive" }
        require(password.isNotBlank()) { "password must not be blank" }
    }

    internal fun toJson(): JSONObject = JSONObject().put("workerId", workerId).put("password", password)
    override fun toString(): String = "WorkerLoginRequest(workerId=$workerId, password=[REDACTED])"
}

data class WorkerTokenResponse(val accessToken: String, val tokenType: String, val expiresAt: Instant) {
    override fun toString(): String = "WorkerTokenResponse(accessToken=[REDACTED], tokenType=$tokenType, expiresAt=$expiresAt)"

    companion object {
        internal fun fromJson(json: JSONObject): WorkerTokenResponse {
            val token = json.requiredString("accessToken")
            val type = json.requiredString("tokenType")
            require(token.isNotBlank()) { "accessToken must not be blank" }
            require(type == "Bearer") { "unexpected tokenType" }
            return WorkerTokenResponse(token, type, json.requiredInstant("expiresAt"))
        }
    }
}

data class WorkerProfileUpdate(
    val workerName: String? = null,
    val sex: String? = null,
    val age: Int? = null
) {
    init {
        require(workerName != null || sex != null || age != null) { "at least one field is required" }
        require(workerName == null || (workerName.isNotBlank() && workerName.length <= 50)) { "invalid workerName" }
        require(sex == null || sex == "M" || sex == "F" || sex == "U") { "invalid sex" }
        require(age == null || age in 0..150) { "invalid age" }
    }

    internal fun toJson(): JSONObject = JSONObject().apply {
        workerName?.let { put("workerName", it) }
        sex?.let { put("sex", it) }
        age?.let { put("age", it) }
    }
}

data class WorkerInfo(
    val workerId: Long,
    val workerName: String,
    val sex: String?,
    val age: Int?,
    val enabled: Boolean
) {
    companion object {
        internal fun fromJson(json: JSONObject): WorkerInfo = WorkerInfo(
            workerId = json.requiredLong("workerId"),
            workerName = json.requiredString("workerName"),
            sex = json.nullableString("sex"),
            age = json.nullableInt("age"),
            enabled = json.requiredBoolean("enabled")
        )
    }
}

data class WorkerPasswordChange(val currentPassword: String, val newPassword: String) {
    init {
        require(currentPassword.isNotBlank()) { "currentPassword must not be blank" }
        require(newPassword.isNotBlank() && newPassword.length in 12..64 && newPassword.toByteArray(Charsets.UTF_8).size <= 72) {
            "newPassword must be 12 to 64 UTF-16 units and at most 72 UTF-8 bytes"
        }
    }

    internal fun toJson(): JSONObject = JSONObject().put("currentPassword", currentPassword).put("newPassword", newPassword)
    override fun toString(): String = "WorkerPasswordChange(currentPassword=[REDACTED], newPassword=[REDACTED])"
}

data class WorkerPasswordConfirmation(val currentPassword: String) {
    init {
        require(currentPassword.isNotBlank()) { "currentPassword must not be blank" }
    }

    internal fun toJson(): JSONObject = JSONObject().put("currentPassword", currentPassword)
    override fun toString(): String = "WorkerPasswordConfirmation(currentPassword=[REDACTED])"
}

enum class WorkerEventType { FALL_DETECTED, DANGER_ZONE_APPROACH, ABNORMAL_VITAL }

data class WorkerEventRequest(val time: Instant, val eventType: WorkerEventType, val alertSender: String) {
    init {
        require(alertSender.isNotBlank() && alertSender.length <= 100) { "invalid alertSender" }
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("time", time.toString())
        .put("eventType", eventType.name)
        .put("alertSender", alertSender)
}

data class WorkerEventInfo(
    val eventId: Long,
    val workerId: Long,
    val workerName: String,
    val time: Instant,
    val receivedAt: Instant,
    val eventType: WorkerEventType,
    val alertSender: String,
    val processedAt: Instant?,
    val processedBy: Long?,
    val adminMemo: String?
) {
    companion object {
        internal fun fromJson(json: JSONObject): WorkerEventInfo = WorkerEventInfo(
            eventId = json.requiredLong("eventId"),
            workerId = json.requiredLong("workerId"),
            workerName = json.requiredString("workerName"),
            time = json.requiredInstant("time"),
            receivedAt = json.requiredInstant("receivedAt"),
            eventType = WorkerEventType.valueOf(json.requiredString("eventType")),
            alertSender = json.requiredString("alertSender"),
            processedAt = json.nullableString("processedAt")?.let(Instant::parse),
            processedBy = json.nullableLong("processedBy"),
            adminMemo = json.nullableString("adminMemo")
        )
    }
}

private fun JSONObject.requiredString(name: String): String =
    get(name) as? String ?: throw IllegalArgumentException("$name must be a string")

private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else requiredString(name)

private fun JSONObject.requiredLong(name: String): Long {
    val value = get(name)
    require(value is Number) { "$name must be an integer" }
    return value.toString().toLongOrNull() ?: throw IllegalArgumentException("$name must be a 64-bit integer")
}

private fun JSONObject.nullableLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else requiredLong(name)

private fun JSONObject.nullableInt(name: String): Int? = nullableLong(name)?.let {
    require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$name must be a 32-bit integer" }
    it.toInt()
}

private fun JSONObject.requiredBoolean(name: String): Boolean =
    get(name) as? Boolean ?: throw IllegalArgumentException("$name must be a boolean")

private fun JSONObject.requiredInstant(name: String): Instant = Instant.parse(requiredString(name))

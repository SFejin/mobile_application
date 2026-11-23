package com.example.safetymonitor.ui

enum class WorkSafePage {
    LOGIN, HOME, BEACON_TRANSMITTER, PERSONAL_INFO, THEME, PASSWORD, WITHDRAW, DETECTION_SETTINGS
}

data class WorkerProfile(
    val workerId: String = "",
    val name: String = "",
    val gender: String = "",
    val age: Int? = null
)

enum class WorkSafeDialogKind { CONFIRM_WITHDRAWAL, SUCCESS, FAILURE }

data class WorkSafeDialogState(
    val kind: WorkSafeDialogKind,
    val message: String
)

internal fun com.example.safetymonitor.network.WorkerInfo.toProfile(expectedWorkerId: Long): WorkerProfile {
    require(workerId == expectedWorkerId && enabled) { "Unexpected worker profile" }
    require(workerName.isNotBlank() && workerName.length <= 50) { "Invalid worker name" }
    require(sex == null || sex in setOf("M", "F", "U")) { "Invalid worker sex" }
    require(age == null || age in 0..150) { "Invalid worker age" }
    return WorkerProfile(workerId.toString(), workerName,
        when (sex) { "M" -> "남"; "F" -> "여"; "U" -> "미지정"; else -> "" }, age)
}

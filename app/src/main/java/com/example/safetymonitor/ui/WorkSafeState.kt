package com.example.safetymonitor.ui

enum class WorkSafePage {
    LOGIN, HOME, BEACON_TRANSMITTER, PERSONAL_INFO, THEME, PASSWORD, WITHDRAW
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

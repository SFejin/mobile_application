package com.example.safetymonitor.ui

enum class WorkSafePage {
    LOGIN, HOME, BEACON_TRANSMITTER, PERSONAL_INFO, THEME, PASSWORD, WITHDRAW
}

data class WorkerProfile(
    val workerId: String = "1024",
    val name: String = "홍길동",
    val gender: String = "남",
    val age: Int = 29,
    val jobTitle: String = "사원",
    val department: String = "생산1팀"
)

enum class WorkSafeDialogKind { CONFIRM_WITHDRAWAL, SUCCESS, FAILURE }

data class WorkSafeDialogState(
    val kind: WorkSafeDialogKind,
    val message: String
)

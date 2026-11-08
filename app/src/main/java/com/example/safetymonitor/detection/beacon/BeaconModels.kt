package com.example.safetymonitor.detection.beacon

enum class BeaconRole {
    RECEIVER,
    TRANSMITTER
}

enum class BeaconStatus {
    IDLE,
    STARTING,
    ACTIVE,
    STOPPED,
    BLOCKED,
    ERROR
}

enum class BeaconIssue {
    NONE,
    PERMISSIONS,
    BLUETOOTH_OFF,
    LOCATION_OFF,
    UNSUPPORTED,
    SCAN_FAILED,
    ADVERTISE_FAILED,
    START_FAILED
}

data class BeaconConfig(
    val id: Int = 1,
    val xCm: Int = 0,
    val yCm: Int = 0,
    val referenceRssi: Int = -59
)

data class BeaconObservation(
    val config: BeaconConfig,
    val rssi: Double,
    val distanceMeters: Double?,
    val lastSeenElapsedMillis: Long,
    val conflicted: Boolean = false
)

sealed interface BeaconPosition {
    data class Available(
        val xMeters: Double,
        val yMeters: Double,
        val anchorCount: Int
    ) : BeaconPosition

    data class Proximity(val anchor: BeaconRange) : BeaconPosition

    data class Weighted(
        val xMeters: Double,
        val yMeters: Double,
        val anchorCount: Int,
        val fallbackReason: String? = null
    ) : BeaconPosition

    data class Unavailable(
        val reason: String
    ) : BeaconPosition
}

data class BeaconEstimate(
    val observations: List<BeaconObservation>,
    val position: BeaconPosition
)

data class BeaconSnapshot(
    val role: BeaconRole = BeaconRole.RECEIVER,
    val status: BeaconStatus = BeaconStatus.IDLE,
    val issue: BeaconIssue = BeaconIssue.NONE,
    val message: String = "BLE 수신 대기",
    val observations: List<BeaconObservation> = emptyList(),
    val position: BeaconPosition = BeaconPosition.Unavailable("유효한 비콘 신호 대기 중"),
    val transmitterConfig: BeaconConfig? = null
)

data class BeaconRange(
    val config: BeaconConfig,
    val distanceMeters: Double
)

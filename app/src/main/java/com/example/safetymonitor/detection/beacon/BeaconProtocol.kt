package com.example.safetymonitor.detection.beacon

object BeaconProtocol {
    const val SERVICE_UUID: String = "a8e7b100-6f4d-4a2b-9d8c-57a4c36e1201"
    const val PAYLOAD_VERSION: Int = 1
    const val PAYLOAD_SIZE_BYTES: Int = 8

    private const val MIN_ID: Int = 1
    private const val MAX_ID: Int = 0xFFFF
    private const val MIN_COORD_CM: Int = Short.MIN_VALUE.toInt()
    private const val MAX_COORD_CM: Int = Short.MAX_VALUE.toInt()
    private const val MIN_REFERENCE_RSSI: Int = -127
    private const val MAX_REFERENCE_RSSI: Int = -1

    fun isValid(config: BeaconConfig): Boolean {
        return config.id in MIN_ID..MAX_ID &&
            config.xCm in MIN_COORD_CM..MAX_COORD_CM &&
            config.yCm in MIN_COORD_CM..MAX_COORD_CM &&
            config.referenceRssi in MIN_REFERENCE_RSSI..MAX_REFERENCE_RSSI
    }

    fun encode(config: BeaconConfig): ByteArray {
        require(isValid(config)) {
            "Invalid BeaconConfig: id=${config.id}, xCm=${config.xCm}, yCm=${config.yCm}, referenceRssi=${config.referenceRssi}"
        }
        return byteArrayOf(
            PAYLOAD_VERSION.toByte(),
            (config.id and 0xFF).toByte(),
            ((config.id ushr 8) and 0xFF).toByte(),
            (config.xCm and 0xFF).toByte(),
            ((config.xCm ushr 8) and 0xFF).toByte(),
            (config.yCm and 0xFF).toByte(),
            ((config.yCm ushr 8) and 0xFF).toByte(),
            config.referenceRssi.toByte()
        )
    }

    fun decode(data: ByteArray?): BeaconConfig? {
        if (data == null || data.size != PAYLOAD_SIZE_BYTES) {
            return null
        }
        val version = data[0].toInt() and 0xFF
        if (version != PAYLOAD_VERSION) {
            return null
        }

        val id = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        val xCm = ((data[3].toInt() and 0xFF) or (data[4].toInt() shl 8)).toShort().toInt()
        val yCm = ((data[5].toInt() and 0xFF) or (data[6].toInt() shl 8)).toShort().toInt()
        val referenceRssi = data[7].toInt()

        val config = BeaconConfig(
            id = id,
            xCm = xCm,
            yCm = yCm,
            referenceRssi = referenceRssi
        )
        return if (isValid(config)) config else null
    }
}

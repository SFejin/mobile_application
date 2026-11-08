package com.example.safetymonitor.detection.beacon

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BeaconProtocolTest {

    @Test
    fun fixedExternalVectorEncodesAndDecodesExactBytes() {
        val config = BeaconConfig(
            id = 0x1234,
            xCm = -100,
            yCm = 250,
            referenceRssi = -59
        )
        val expectedWire = byteArrayOf(
            0x01,
            0x34,
            0x12,
            0x9C.toByte(),
            0xFF.toByte(),
            0xFA.toByte(),
            0x00,
            0xC5.toByte()
        )

        assertTrue(BeaconProtocol.isValid(config))
        assertArrayEquals(expectedWire, BeaconProtocol.encode(config))
        assertEquals(config, BeaconProtocol.decode(expectedWire))
    }

    @Test
    fun boundaryCoordinatesAndIdsRoundTripExactWireBytes() {
        val minConfig = BeaconConfig(
            id = 1,
            xCm = -32768,
            yCm = -32768,
            referenceRssi = -127
        )
        val expectedMinWire = byteArrayOf(
            0x01,
            0x01,
            0x00,
            0x00,
            0x80.toByte(),
            0x00,
            0x80.toByte(),
            0x81.toByte()
        )
        assertTrue(BeaconProtocol.isValid(minConfig))
        assertArrayEquals(expectedMinWire, BeaconProtocol.encode(minConfig))
        assertEquals(minConfig, BeaconProtocol.decode(expectedMinWire))

        val maxConfig = BeaconConfig(
            id = 65535,
            xCm = 32767,
            yCm = 32767,
            referenceRssi = -1
        )
        val expectedMaxWire = byteArrayOf(
            0x01,
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0x7F,
            0xFF.toByte(),
            0x7F,
            0xFF.toByte()
        )
        assertTrue(BeaconProtocol.isValid(maxConfig))
        assertArrayEquals(expectedMaxWire, BeaconProtocol.encode(maxConfig))
        assertEquals(maxConfig, BeaconProtocol.decode(expectedMaxWire))
    }

    @Test
    fun isValidAndEncodeRejectOutOfRangeFields() {
        val invalidConfigs = listOf(
            BeaconConfig(id = 0, xCm = 0, yCm = 0, referenceRssi = -59),
            BeaconConfig(id = -1, xCm = 0, yCm = 0, referenceRssi = -59),
            BeaconConfig(id = 65536, xCm = 0, yCm = 0, referenceRssi = -59),
            BeaconConfig(id = 1, xCm = -32769, yCm = 0, referenceRssi = -59),
            BeaconConfig(id = 1, xCm = 32768, yCm = 0, referenceRssi = -59),
            BeaconConfig(id = 1, xCm = 0, yCm = -32769, referenceRssi = -59),
            BeaconConfig(id = 1, xCm = 0, yCm = 32768, referenceRssi = -59),
            BeaconConfig(id = 1, xCm = 0, yCm = 0, referenceRssi = -128),
            BeaconConfig(id = 1, xCm = 0, yCm = 0, referenceRssi = 0),
            BeaconConfig(id = 1, xCm = 0, yCm = 0, referenceRssi = 20)
        )

        for (invalid in invalidConfigs) {
            assertFalse("Expected invalid config: $invalid", BeaconProtocol.isValid(invalid))
            try {
                BeaconProtocol.encode(invalid)
                fail("Expected encode to throw IllegalArgumentException for $invalid")
            } catch (_: IllegalArgumentException) {
                // Expected
            }
        }
    }

    @Test
    fun decodeReturnsNullForMalformedPackets() {
        assertNull(BeaconProtocol.decode(null))
        assertNull(BeaconProtocol.decode(byteArrayOf()))
        assertNull(BeaconProtocol.decode(ByteArray(7) { 0x01 }))
        assertNull(BeaconProtocol.decode(ByteArray(9) { 0x01 }))

        // Wrong version (0x00 and 0x02)
        assertNull(
            BeaconProtocol.decode(
                byteArrayOf(0x00, 0x34, 0x12, 0x00, 0x00, 0x00, 0x00, 0xC5.toByte())
            )
        )
        assertNull(
            BeaconProtocol.decode(
                byteArrayOf(0x02, 0x34, 0x12, 0x00, 0x00, 0x00, 0x00, 0xC5.toByte())
            )
        )

        // Invalid ID 0x0000
        assertNull(
            BeaconProtocol.decode(
                byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xC5.toByte())
            )
        )

        // Invalid reference RSSI: 0, +1, +127, -128 (0x80)
        assertNull(
            BeaconProtocol.decode(
                byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            )
        )
        assertNull(
            BeaconProtocol.decode(
                byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01)
            )
        )
        assertNull(
            BeaconProtocol.decode(
                byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7F)
            )
        )
        assertNull(
            BeaconProtocol.decode(
                byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80.toByte())
            )
        )
    }
}

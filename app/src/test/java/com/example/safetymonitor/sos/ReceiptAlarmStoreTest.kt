package com.example.safetymonitor.sos

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class ReceiptAlarmStoreTest {
    @After fun reset() { ReceiptAlarmStore.clear(); ReceiptAlarmStore.onSilence = null }
    @Test fun onlyExactCodeForCurrentReceiptCanSilence() {
        var stops = 0
        ReceiptAlarmStore.onSilence = { stops++; ReceiptAlarmStore.clear() }
        ReceiptAlarmStore.show("first")
        listOf("", "000", "1234", "00000", " 0000").forEach { ReceiptAlarmStore.silence("first", it) }
        assertEquals(0, stops)
        ReceiptAlarmStore.show("second")
        ReceiptAlarmStore.silence("first", "0000")
        assertEquals("second", ReceiptAlarmStore.active.value)
        ReceiptAlarmStore.silence("second", "0000")
        assertEquals(1, stops)
        assertNull(ReceiptAlarmStore.active.value)
        ReceiptAlarmStore.silence("second", "0000")
        assertEquals(1, stops)
    }
}

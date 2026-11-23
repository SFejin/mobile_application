package com.example.safetymonitor.ui

import com.example.safetymonitor.network.WorkerInfo
import com.example.safetymonitor.sos.SosServer
import org.junit.Assert.*
import org.junit.Test

class WorkerProfileTest {
    @Test fun migrateOnlyTheOldDefaultServer() {
        assertEquals("http://34.158.208.112:8081", SosServer.DEFAULT_SERVER)
        for (saved in listOf(null, "", "http://34.22.75.168:8081", " http://34.22.75.168:8081/ ")) {
            assertEquals(SosServer.DEFAULT_SERVER, SosServer.configuredAddress(saved))
        }
        assertEquals("https://custom.example", SosServer.configuredAddress("https://custom.example"))
        assertEquals("http://127.0.0.1:8081", SosServer.configuredAddress("http://127.0.0.1:8081"))
    }

    @Test fun profilePreservesZeroAndNullAndMapsServerGenderCodes() {
        for ((code, label) in mapOf("M" to "남", "F" to "여", "U" to "미지정")) {
            val result = WorkerInfo(77, "서버 이름", code, 0, true).toProfile(77)
            assertEquals(label, result.gender)
            assertEquals(0, result.age)
            assertEquals("서버 이름", result.name)
        }
        assertNull(WorkerInfo(77, "이름", null, null, true).toProfile(77).age)
    }

    @Test fun wrongAccountAndMalformedProfileCannotBeDisplayedAsCurrentWorker() {
        for (result in listOf(
            WorkerInfo(78, "이름", "M", 30, true), WorkerInfo(77, "이름", "M", 30, false),
            WorkerInfo(77, "", "M", 30, true), WorkerInfo(77, "이름", "X", 30, true),
            WorkerInfo(77, "이름", "M", 151, true))) {
            assertThrows(IllegalArgumentException::class.java) { result.toProfile(77) }
        }
    }
}

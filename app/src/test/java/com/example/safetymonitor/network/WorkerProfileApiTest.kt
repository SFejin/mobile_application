package com.example.safetymonitor.network

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class WorkerProfileApiTest {
    private val profile = """{"workerId":77,"workerName":"서버 이름","sex":"U","age":0,"enabled":true,"loginLocked":false,"deleted":false}"""
    private data class Captured(val method: String, val path: String, val authorization: String?, val body: String)

    private fun server(code: Int, body: String, block: (WorkerApiClient, AtomicReference<Captured>) -> Unit) {
        val captured = AtomicReference<Captured>()
        val failure = AtomicReference<Throwable>()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().buffered()
                    fun line(): String {
                        val bytes = java.io.ByteArrayOutputStream()
                        while (true) {
                            val next = input.read()
                            check(next >= 0)
                            if (next == 10) return bytes.toString("UTF-8").trimEnd('\r')
                            bytes.write(next)
                        }
                    }
                    val first = line().split(' ')
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    val bytes = ByteArray(headers["content-length"]?.toInt() ?: 0)
                    var count = 0
                    while (count < bytes.size) {
                        val read = input.read(bytes, count, bytes.size - count)
                        check(read > 0)
                        count += read
                    }
                    captured.set(Captured(first[0], first[1], headers["authorization"], bytes.toString(Charsets.UTF_8)))
                    if (code != 0) {
                        val response = body.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 $code Test\r\nContent-Type: application/json; charset=utf-8\r\n" +
                                "Location: http://127.0.0.1:1/other\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            write(response)
                            flush()
                        }
                    }
                }
            } catch (e: Throwable) { if (!server.isClosed) failure.set(e) }
        }
        try { block(WorkerApiClient("http://127.0.0.1:${server.localPort}", true), captured) }
        finally { server.close(); worker.join(6_000) }
        failure.get()?.let { throw it }
    }

    @Test fun vitalAndDangerEventsUseAuthenticatedEventEndpoint() {
        val time = java.time.Instant.parse("2026-11-25T00:00:00Z")
        for ((type, sender) in listOf(
            WorkerEventType.ABNORMAL_VITAL to "심박수 이상 SOS",
            WorkerEventType.ABNORMAL_VITAL to "체온 이상 SOS",
            WorkerEventType.DANGER_ZONE_APPROACH to "위험지역 침입 · 비콘 #42",
            WorkerEventType.DANGER_ZONE_APPROACH to "위험지역 작업 시간 초과 무응답 · 비콘 #42"
        )) {
            val response = """{"eventId":1,"workerId":77,"workerName":"worker","time":"$time","receivedAt":"$time","eventType":"${type.name}","alertSender":"$sender"}"""
            server(201, response) { api, captured ->
                val result = runBlocking { api.createEvent("worker-token", WorkerEventRequest(time, type, sender)) }
                assertEquals(type, result.eventType)
                val request = captured.get()
                assertEquals("POST", request.method)
                assertEquals("/api/worker/events", request.path)
                assertEquals("Bearer worker-token", request.authorization)
                val json = JSONObject(request.body)
                assertEquals(setOf("time", "eventType", "alertSender"), json.keys().asSequence().toSet())
                assertEquals(type.name, json.getString("eventType"))
                assertEquals(sender, json.getString("alertSender"))
            }
        }
    }

    @Test fun monitoringReportUsesNewEndpointAndDoesNotSendRawVitalsOrIdentity() {
        val time = java.time.Instant.parse("2026-11-27T03:10:00Z")
        for (heart in listOf(null, HeartAssessment(HeartRateStatus.NORMAL, time), HeartAssessment(HeartRateStatus.DANGER, time))) {
            val response = """
                {"monitoringStatus":"RUNNING","reportedAt":"$time","lastSeenAt":"$time","watchLastSeenAt":"$time",
                 "heartRateStatus":${heart?.let { "\"${it.status}\"" } ?: "null"},
                 "measuredAt":${heart?.let { "\"$time\"" } ?: "null"},
                 "heartRateDataStatus":"${if (heart == null) "NOT_RECEIVED" else "FRESH"}"}
            """.trimIndent()
            server(200, response) { api, captured ->
                val result = runBlocking { api.reportStatus("worker-token", WorkerStatusRequest(MonitoringStatus.RUNNING, time, time, heart)) }
                assertEquals(heart, result.heart)
                val request = captured.get()
                assertEquals("POST", request.method)
                assertEquals("/api/worker/me/status", request.path)
                assertEquals("Bearer worker-token", request.authorization)
                val json = JSONObject(request.body)
                val keys = setOf("monitoringStatus", "reportedAt", "devices") + if (heart == null) emptySet() else setOf("heartRateStatus", "measuredAt")
                assertEquals(keys, json.keys().asSequence().toSet())
                assertEquals("워치", json.getJSONArray("devices").getJSONObject(0).getString("type"))
                assertEquals(time.toString(), json.getJSONArray("devices").getJSONObject(0).getString("lastSeenAt"))
                if (heart != null) assertEquals(time.toString(), json.getString("measuredAt"))
            }
        }
        val stopped = WorkerStatusRequest(MonitoringStatus.STOPPED, time).toJson()
        assertEquals(setOf("monitoringStatus", "reportedAt"), stopped.keys().asSequence().toSet())
    }

    @Test fun statusReportPropagatesAuthenticationAndOrderingFailures() {
        for (status in listOf(401, 409, 500)) server(status, """{"code":"STATUS_ERROR","message":"거절"}""") { api, _ ->
            val error = assertThrows(WorkerApiException::class.java) {
                runBlocking { api.reportStatus("token", WorkerStatusRequest(MonitoringStatus.STOPPED, java.time.Instant.now())) }
            }
            assertEquals(status, error.httpStatus)
        }
    }

    @Test fun getOwnProfileUsesBearerAndAcceptsNewServerFields() {
        server(200, profile) { api, request ->
            val result = runBlocking { api.getProfile("worker-token") }
            assertEquals(77L, result.workerId)
            assertEquals("서버 이름", result.workerName)
            assertEquals("U", result.sex)
            assertEquals(0, result.age)
            assertEquals(Captured("GET", "/api/worker/me", "Bearer worker-token", ""), request.get())
        }
    }

    @Test fun patchSendsUtf8AndOnlyEditableFieldsAndUsesServerResponse() {
        server(200, profile) { api, request ->
            val result = runBlocking { api.updateProfile("worker-token", WorkerProfileUpdate("수정 이름", "F", 150)) }
            assertEquals("서버 이름", result.workerName)
            val actual = request.get()
            assertEquals("PATCH", actual.method)
            assertEquals("/api/worker/me", actual.path)
            assertEquals("Bearer worker-token", actual.authorization)
            val json = JSONObject(actual.body)
            assertEquals(setOf("workerName", "sex", "age"), json.keys().asSequence().toSet())
            assertEquals("수정 이름", json.getString("workerName"))
            assertEquals("F", json.getString("sex"))
            assertEquals(150, json.getInt("age"))
        }
    }

    @Test fun nullOptionalProfileFieldsRemainNull() {
        server(200, profile.replace("\"U\"", "null").replace("\"age\":0", "\"age\":null")) { api, _ ->
            val result = runBlocking { api.getProfile("token") }
            assertNull(result.sex)
            assertNull(result.age)
        }
    }

    @Test fun getAndPatchSurfaceServerErrors() {
        for (status in listOf(400, 401, 403, 404, 500)) {
            for (saving in listOf(false, true)) server(status, """{"code":"DENIED","message":"거절"}""") { api, _ ->
                val error = assertThrows(WorkerApiException::class.java) {
                    runBlocking {
                        if (saving) api.updateProfile("token", WorkerProfileUpdate(workerName = "이름"))
                        else api.getProfile("token")
                    }
                }
                assertEquals(status, error.httpStatus)
                assertEquals("DENIED", error.code)
            }
        }
    }

    @Test fun malformedSuccessIsNotReportedAsSaved() {
        for (body in listOf("{}", "not json", profile.replace("\"age\":0", "\"age\":1.5"))) {
            server(200, body) { api, _ ->
                assertThrows(IOException::class.java) {
                    runBlocking { api.updateProfile("token", WorkerProfileUpdate(workerName = "이름")) }
                }
            }
        }
    }

    @Test fun lostWriteResponseIsFailure() {
        server(0, "") { api, request ->
            assertThrows(IOException::class.java) {
                runBlocking { api.updateProfile("token", WorkerProfileUpdate(workerName = "이름")) }
            }
            assertEquals("PATCH", request.get().method)
        }
    }

    @Test fun redirectsAreNotFollowedWithWorkerCredentials() {
        server(302, "") { api, _ ->
            val error = assertThrows(WorkerApiException::class.java) { runBlocking { api.getProfile("token") } }
            assertEquals(302, error.httpStatus)
        }
    }

    @Test fun blankTokenDoesNotReachNetwork() {
        server(200, profile) { api, request ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { api.getProfile(" ") } }
            assertNull(request.get())
        }
    }
}

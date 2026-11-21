package com.example.safetymonitor.sos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import com.example.safetymonitor.network.WorkerApiClient
import com.example.safetymonitor.network.WorkerApiException
import com.example.safetymonitor.network.WorkerPasswordConfirmation
import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import java.time.Instant

class SosServerTest {
    @Test fun developmentCredentialsBypassBackendOnlyInDebug() {
        server(200, """{"tokenType":"Bearer","accessToken":"real-token","expiresAt":"2099-01-01T00:00:00Z"}""") { url, requests ->
            val result = runBlocking { loginWorkerSession(url, 1024, "0000") }
            assertEquals(com.example.safetymonitor.BuildConfig.DEBUG, result.isDevelopment)
            assertTrue(result.valid())
            if (com.example.safetymonitor.BuildConfig.DEBUG) {
                assertTrue(requests.isEmpty())
                assertEquals("", result.server)
                assertEquals("", result.token)
                assertEquals(SosPhase.TEST_DONE, SosServer.send(result, incident).first)
                assertTrue(requests.isEmpty())
                result.invalidate()
                assertFalse(result.valid())
                assertEquals(SosPhase.FAILED, SosServer.send(result, incident).first)
            } else {
                assertEquals(1, requests.size)
                assertEquals("real-token", result.token)
            }
        }
    }

    @Test fun nearMissDevelopmentCredentialsStillRequireServerAuthentication() {
        for ((id, password) in listOf(1024L to "wrong", 1025L to "0000", 1024L to "0000 ")) {
            server(401, "{}") { url, requests ->
                assertThrows(WorkerApiException::class.java) { runBlocking { loginWorkerSession(url, id, password) } }
                assertEquals(1, requests.size)
            }
        }
    }

    private val incident = SosState(phase = SosPhase.SENDING, occurredAt = 1234567890000L, workerId = 77)

    private fun server(code: Int, response: String, expectedMethod: String = "POST", run: (String, MutableList<Triple<String, String?, String>>) -> Unit) {
        val requests = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String?, String>>())
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        var failure: Throwable? = null
        val worker = thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    val first = reader.readLine()
                    assertEquals(expectedMethod, first.substringBefore(' '))
                    val headers = mutableListOf<String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers.add(line) }
                    val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }
                        ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val chars = CharArray(length)
                    var read = 0
                    while (read < length) { val count = reader.read(chars, read, length - read); check(count > 0); read += count }
                    requests.add(Triple(first.split(' ')[1], headers.firstOrNull { it.startsWith("Authorization:", true) }
                        ?.substringAfter(':')?.trim(), String(chars)))
                    if (code != 0) {
                        val bytes = response.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                }
            } catch (e: Throwable) { if (!server.isClosed) failure = e }
        }
        try { run("http://127.0.0.1:${server.localPort}", requests) }
        finally { server.close(); worker.join(6_000) }
        failure?.let { throw it }
    }

    private fun session(url: String) = SosSession(url, 77, "test-token", Instant.parse("2099-01-01T00:00:00Z"))

    @Test fun loginUsesWorkerApiAndReturnsTokenSession() {
        server(200, """{"tokenType":"Bearer","accessToken":"test-token","expiresAt":"2099-01-01T00:00:00Z"}""") { url, requests ->
            val session = runBlocking { loginWorkerSession(url, 77, "test-password") }
            assertEquals(77L, session.workerId)
            assertTrue(session.valid())
            assertEquals("/api/worker/auth/login", requests.single().first)
            val json = JSONObject(requests.single().third)
            assertEquals(77L, json.getLong("workerId"))
            assertEquals("test-password", json.getString("password"))
            assertFalse(session.toString().contains("test-token"))
        }
    }

    @Test fun invalidCredentialsCannotCreateSession() {
        for (code in listOf(401, 403, 500)) server(code, "{}") { url, requests ->
            assertThrows(WorkerApiException::class.java) { runBlocking { loginWorkerSession(url, 77, "wrong") } }
            assertEquals(1, requests.size)
            assertNull(requests.single().second)
        }
    }
    @Test fun noContentLoginCannotCreateSession() {
        server(204, "") { url, requests ->
            val error = assertThrows(WorkerApiException::class.java) {
                runBlocking { loginWorkerSession(url, 77, "password") }
            }
            assertEquals(204, error.httpStatus)
            assertEquals(1, requests.size)
        }
    }


    @Test fun expiredOrEmptyLoginTokenCannotCreateSession() {
        server(200, """{"tokenType":"Bearer","accessToken":"test","expiresAt":"2000-01-01T00:00:00Z"}""") { url, _ ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { loginWorkerSession(url, 77, "password") } }
        }
        server(200, """{"tokenType":"Bearer","accessToken":"","expiresAt":"2099-01-01T00:00:00Z"}""") { url, _ ->
            assertThrows(java.io.IOException::class.java) { runBlocking { loginWorkerSession(url, 77, "password") } }
        }
    }

    @Test fun malformedOrUnsupportedAuthenticationCannotCreateSession() {
        for (response in listOf(
            "not-json", "{}",
            """{"accessToken":"test","expiresAt":"2099-01-01T00:00:00Z"}""",
            """{"accessToken":"test","tokenType":"Basic","expiresAt":"2099-01-01T00:00:00Z"}""",
            """{"accessToken":123,"tokenType":"Bearer","expiresAt":"2099-01-01T00:00:00Z"}""",
            """{"accessToken":"test","tokenType":"Bearer","expiresAt":"invalid"}""",
            JSONObject().put("accessToken", "bad\r\ntoken").put("tokenType", "Bearer")
                .put("expiresAt", "2099-01-01T00:00:00Z").toString()
        )) server(200, response) { url, _ ->
            assertThrows(java.io.IOException::class.java) {
                runBlocking { loginWorkerSession(url, 77, "password") }
            }
        }
    }

    @Test fun invalidInputDoesNotReachServer() {
        server(200, "{}") { url, requests ->
            for (id in listOf(0L, -1L)) {
                assertThrows(IllegalArgumentException::class.java) { runBlocking { loginWorkerSession(url, id, "password") } }
            }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { loginWorkerSession(url, 77, " ") } }
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun rateLimitAndServerFailureHaveActionableMessages() {
        for (code in listOf(429, 503)) server(code, "{}") { url, _ ->
            val error = assertThrows(WorkerApiException::class.java) {
                runBlocking { loginWorkerSession(url, 77, "password") }
            }
            assertEquals(code, error.httpStatus)
        }
    }

    @Test fun withdrawalSendsDeleteWithCurrentPasswordAndSessionToken() {
        server(204, "", expectedMethod = "DELETE") { url, requests ->
            runBlocking {
                WorkerApiClient(url, allowInsecureHttp = true).deleteAccount(
                    "test-token", WorkerPasswordConfirmation(" password-with-spaces "))
            }
            val request = requests.single()
            assertEquals("/api/worker/me", request.first)
            assertEquals("Bearer test-token", request.second)
            val json = JSONObject(request.third)
            assertEquals(" password-with-spaces ", json.getString("currentPassword"))
            assertEquals(1, json.length())
        }
    }

    @Test fun withdrawalRejectionIsNotTreatedAsSuccess() {
        for (status in listOf(400, 401, 403, 500, 200)) {
            server(status, """{"code":"AUTHENTICATION_FAILED","message":"Denied"}""",
                expectedMethod = "DELETE") { url, requests ->
                val error = assertThrows(WorkerApiException::class.java) {
                    runBlocking {
                        WorkerApiClient(url, allowInsecureHttp = true).deleteAccount(
                            "test-token", WorkerPasswordConfirmation("wrong"))
                    }
                }
                assertEquals(status, error.httpStatus)
                assertEquals(1, requests.size)
            }
        }
    }

    @Test fun withdrawalLostResponseIsNotSuccessOrAutomaticallyRetried() {
        server(0, "", expectedMethod = "DELETE") { url, requests ->
            assertThrows(java.io.IOException::class.java) {
                runBlocking {
                    WorkerApiClient(url, allowInsecureHttp = true).deleteAccount(
                        "test-token", WorkerPasswordConfirmation("password"))
                }
            }
            assertEquals(1, requests.size)
        }
    }

    @Test fun withdrawalDoesNotSendBlankPassword() {
        server(204, "", expectedMethod = "DELETE") { url, requests ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    WorkerApiClient(url, allowInsecureHttp = true).deleteAccount(
                        "test-token", WorkerPasswordConfirmation(" "))
                }
            }
            assertTrue(requests.isEmpty())
            assertFalse(WorkerPasswordConfirmation("secret").toString().contains("secret"))
        }
    }

    @Test fun logoutUsesSameAuthenticatedWorkerToken() {
        server(204, "") { url, requests ->
            runBlocking { WorkerApiClient(url, true).logout("test-token") }
            assertEquals("/api/worker/auth/logout", requests.single().first)
            assertEquals("Bearer test-token", requests.single().second)
        }
    }

    @Test fun eventUsesBearerAndExactServerContract() {
        server(201, """{"eventId":123,"workerId":77,"eventType":"FALL_DETECTED"}""") { url, requests ->
            assertEquals(SosPhase.SENT, SosServer.send(session(url), incident).first)
            val request = requests.single()
            assertEquals("/api/worker/events", request.first)
            assertEquals("Bearer test-token", request.second)
            val json = JSONObject(request.third)
            assertEquals("FALL_DETECTED", json.getString("eventType"))
            assertEquals("ANDROID_AUTO_SOS", json.getString("alertSender"))
            assertEquals(incident.occurredAt, Instant.parse(json.getString("time")).toEpochMilli())
            assertFalse(json.has("workerId"))
        }
    }

    @Test fun serverRejectionIsNotReportedAsSuccess() {
        server(401, "{}") { url, requests ->
            val authenticated = session(url)
            assertEquals(SosPhase.FAILED, SosServer.send(authenticated, incident).first)
            assertFalse(authenticated.valid())
            assertEquals(SosPhase.FAILED, SosServer.send(authenticated, incident).first)
            assertEquals(1, requests.size)
        }
    }

    @Test fun failureAndLostResponseNeverAutomaticallyRetry() {
        for (code in listOf(500, 0)) server(code, "{}") { url, requests ->
            assertEquals(SosPhase.UNKNOWN, SosServer.send(session(url), incident).first)
            assertEquals(1, requests.size)
        }
    }

    @Test fun mismatchedOrMalformedAcknowledgementIsUncertain() {
        for (response in listOf("{}", """{"eventId":123,"workerId":999,"eventType":"FALL_DETECTED"}""")) {
            server(201, response) { url, _ ->
                assertEquals(SosPhase.UNKNOWN, SosServer.send(session(url), incident).first)
            }
        }
    }

    @Test fun expiredSessionDoesNotSendAnyRequest() {
        server(201, "{}") { url, requests ->
            assertEquals(SosPhase.FAILED, SosServer.send(SosSession(url, 77, "expired", Instant.EPOCH), incident).first)
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun invalidOrCredentialBearingServerAddressesAreRejected() {
        assertEquals("http://34.22.75.168:8081", SosServer.normalize(" http://34.22.75.168:8081/ "))
        for (url in listOf("file:///tmp/x", "http://user:pass@host", "http://host/path", "http://host?token=a", "http://host:99999")) {
            assertThrows(IllegalArgumentException::class.java) { SosServer.normalize(url) }
        }
    }
}

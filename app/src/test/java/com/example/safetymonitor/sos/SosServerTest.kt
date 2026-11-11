package com.example.safetymonitor.sos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import java.time.Instant

class SosServerTest {
    private val incident = SosState(phase = SosPhase.SENDING, occurredAt = 1234567890000L, workerId = 77)

    private fun server(code: Int, response: String, run: (String, MutableList<Triple<String, String?, String>>) -> Unit) {
        val requests = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String?, String>>())
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        var failure: Throwable? = null
        val worker = thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    val first = reader.readLine()
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
        server(200, """{"accessToken":"test-token","expiresAt":"2099-01-01T00:00:00Z"}""") { url, requests ->
            val session = SosServer.login(url, 77, "test-password")
            assertEquals(77L, session.workerId)
            assertTrue(session.valid())
            assertEquals("/api/worker/auth/login", requests.single().first)
            val json = JSONObject(requests.single().third)
            assertEquals(77L, json.getLong("workerId"))
            assertEquals("test-password", json.getString("password"))
            assertFalse(session.toString().contains("test-token"))
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
            assertEquals(SosPhase.FAILED, SosServer.send(session(url), incident).first)
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

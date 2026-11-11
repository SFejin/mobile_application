package com.example.safetymonitor

import android.app.Instrumentation
import com.example.safetymonitor.sos.*
import com.example.safetymonitor.ui.WorkSafeViewModel
import org.json.JSONObject
import java.net.ServerSocket
import java.time.Instant
import kotlin.concurrent.thread

/** Uses loopback only; never creates events on the team's deployed server. */
object SosIntegrationChecks {
    fun run(instrumentation: Instrumentation, model: WorkSafeViewModel, results: StringBuilder) {
        fun waitFor(label: String, timeout: Long = 8_000, condition: () -> Boolean) {
            val end = System.currentTimeMillis() + timeout
            while (!condition()) { check(System.currentTimeMillis() < end) { label }; Thread.sleep(50) }
            results.appendLine("PASS: $label")
        }

        var testSession: SosSession? = null
        mockResponse(200, """{"accessToken":"test-token","expiresAt":"2099-01-01T00:00:00Z"}""") { url, request ->
            testSession = SosServer.login(url, 77, "local-test-password")
            request.join()
            check(request.firstLine == "POST /api/worker/auth/login HTTP/1.1")
            check(JSONObject(request.body).getLong("workerId") == 77L)
            check(JSONObject(request.body).getString("password") == "local-test-password")
        }
        check(testSession?.workerId == 77L)
        results.appendLine("PASS: server login JSON contract")

        val incident = SosState(phase = SosPhase.SENDING, occurredAt = 1234567890000L, workerId = 77)
        mockResponse(201, """{"eventId":123,"workerId":77,"eventType":"FALL_DETECTED"}""") { url, request ->
            val result = SosServer.send(SosSession(url, 77, "test-token", Instant.parse("2099-01-01T00:00:00Z")), incident)
            check(result.first == SosPhase.SENT)
            request.join()
            check(request.firstLine == "POST /api/worker/events HTTP/1.1")
            check(request.headers.any { it.equals("Authorization: Bearer test-token", true) })
            val json = JSONObject(request.body)
            check(json.getString("eventType") == "FALL_DETECTED")
            check(json.getString("alertSender") == "ANDROID_AUTO_SOS")
            check(Instant.parse(json.getString("time")).toEpochMilli() == incident.occurredAt)
            check(!json.has("workerId"))
        }
        results.appendLine("PASS: event path, Bearer token, time and FALL_DETECTED payload; 201 confirmed")

        for ((code, expected) in listOf(401 to SosPhase.FAILED, 500 to SosPhase.UNKNOWN)) {
            mockResponse(code, "{}") { url, request ->
                check(SosServer.send(SosSession(url, 77, "test-token", Instant.MAX), incident).first == expected)
                request.join()
            }
        }
        mockResponse(201, """{"eventId":123,"workerId":999,"eventType":"FALL_DETECTED"}""") { url, request ->
            check(SosServer.send(SosSession(url, 77, "test-token", Instant.MAX), incident).first == SosPhase.UNKNOWN)
            request.join()
        }
        mockResponse(0, "") { url, request ->
            check(SosServer.send(SosSession(url, 77, "test-token", Instant.MAX), incident).first == SosPhase.UNKNOWN)
            request.join()
        }
        check(SosServer.send(SosSession("http://127.0.0.1:1", 77, "expired", Instant.EPOCH), incident).first == SosPhase.FAILED)
        results.appendLine("PASS: 401, 500, mismatched worker, lost response, expired token")

        instrumentation.runOnMainSync { model.testSos() }
        waitFor("real service starts a 20-second local-only countdown") {
            SosStore.state.value.phase == SosPhase.COUNTDOWN && SosStore.state.value.test
        }
        waitFor("countdown completes without server authentication or transmission", 25_000) {
            SosStore.state.value.phase == SosPhase.TEST_DONE
        }
        // Restart only the local demo session to reset the incident cooldown.
        instrumentation.runOnMainSync { model.logout() }
        Thread.sleep(500)
        instrumentation.runOnMainSync { check(model.login("1024", "0000") == null) }
        Thread.sleep(500)
        instrumentation.runOnMainSync { model.testSos() }
        waitFor("second countdown starts") { SosStore.state.value.phase == SosPhase.COUNTDOWN }
        val id = SosStore.state.value.incidentId
        instrumentation.runOnMainSync { model.cancelSos(id) }
        waitFor("screen cancel action reaches the service and stops countdown") {
            SosStore.state.value.phase == SosPhase.CANCELLED
        }
    }

    class RequestCapture {
        lateinit var worker: Thread
        var firstLine = ""
        var headers = listOf<String>()
        var body = ""
        var error: Throwable? = null
        fun join() {
            worker.join(10_000)
            check(!worker.isAlive) { "Mock HTTP request timed out" }
            error?.let { throw it }
        }
    }

    private fun mockResponse(code: Int, body: String, block: (String, RequestCapture) -> Unit) {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val capture = RequestCapture()
            capture.worker = thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader()
                        capture.firstLine = reader.readLine()
                        val headers = mutableListOf<String>()
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers.add(line) }
                        capture.headers = headers
                        val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val chars = CharArray(length)
                        var read = 0
                        while (read < length) { val count = reader.read(chars, read, length - read); check(count > 0); read += count }
                        capture.body = String(chars)
                        if (code != 0) {
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes); flush()
                            }
                        }
                    }
                } catch (e: Throwable) { capture.error = e }
            }
            block("http://127.0.0.1:${server.localPort}", capture)
        }
    }
}

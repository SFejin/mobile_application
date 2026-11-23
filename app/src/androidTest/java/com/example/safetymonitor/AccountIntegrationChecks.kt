package com.example.safetymonitor

import android.app.Instrumentation
import android.app.Application
import com.example.safetymonitor.ui.WorkSafeViewModel
import com.example.safetymonitor.ui.WorkSafePage
import com.example.safetymonitor.sos.SosStore
import com.example.safetymonitor.sos.SosSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Synthetic loopback account server; never contacts the configured remote server. */
object AccountIntegrationChecks {
    private const val token = """{"accessToken":"synthetic-token","tokenType":"Bearer","expiresAt":"2099-01-01T00:00:00Z"}"""
    private const val profile = """{"workerId":77,"workerName":"Fixture","sex":"M","age":30,"enabled":true}"""
    private data class Response(val code: Int, val body: String = "")
    private data class Request(val line: String, val body: String, val headers: List<String>)
    private class Fixture : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"
        val replies = LinkedBlockingQueue<Response>()
        val requests = LinkedBlockingQueue<Request>()
        private val worker = thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val line = reader.readLine()
                        val headers = mutableListOf<String>()
                        while (true) { val h = reader.readLine() ?: break; if (h.isEmpty()) break; headers.add(h) }
                        val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val body = CharArray(length); var count = 0
                        while (count < length) { val n = reader.read(body, count, length - count); check(n > 0); count += n }
                        requests.put(Request(line, String(body), headers))
                        val response = replies.take()
                        if (response.code != 0) {
                            val bytes = response.body.toByteArray()
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 ${response.code} Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes); flush()
                            }
                        }
                    }
                } catch (e: Exception) { if (!server.isClosed) throw e }
            }
        }
        fun respond(code: Int, body: String = "") { replies.put(Response(code, body)) }
        fun request(): Request = checkNotNull(requests.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        override fun close() { server.close(); worker.join(6000) }
    }
    fun loginFixture(model: WorkSafeViewModel) {
        Fixture().use { fixture ->
            fixture.respond(200, token)
            fixture.respond(200, profile)
            check(runBlocking { model.login(fixture.url, "77", "synthetic-password") } == null)
            check(fixture.request().line.startsWith("POST /api/worker/auth/login "))
            check(fixture.request().line.startsWith("GET /api/worker/me "))
        }
    }
    fun run(instrumentation: Instrumentation, results: StringBuilder) {
        val app = instrumentation.targetContext.applicationContext as Application
        val prefs = app.getSharedPreferences("worksafe_theme", 0)
        val originalIncident = SosStore.state.value
        val original = prefs.getString("sos_server", null)
        var model: WorkSafeViewModel? = null
        try {
            instrumentation.runOnMainSync {
                SosStore.connect(SosSession("http://127.0.0.1:1", 77, "expired-synthetic", Instant.EPOCH))
                model = WorkSafeViewModel(app)
                check(model?.sosSession == null && SosStore.session.value == null && model?.page == WorkSafePage.LOGIN)
            }
            val m = checkNotNull(model)
            Fixture().use { f ->
                fun login() {
                    f.respond(200, token)
                    f.respond(200, profile)
                    check(runBlocking { m.login(f.url, "77", "synthetic-password") } == null)
                    val request = f.request()
                    check(request.line.startsWith("POST /api/worker/auth/login "))
                    check(JSONObject(request.body).getLong("workerId") == 77L)
                    val read = f.request()
                    check(read.line.startsWith("GET /api/worker/me "))
                    check(read.headers.any { it.equals("Authorization: Bearer synthetic-token", true) })
                    check(m.profileLoaded && m.profile.name == "Fixture" && m.profile.age == 30)
                    check(m.sosSession === SosStore.session.value && m.sosSession?.workerId == 77L)
                }
                f.respond(200, """{"accessToken":"expired-synthetic","tokenType":"Bearer","expiresAt":"1970-01-01T00:00:00Z"}""")
                check(runBlocking { m.login(f.url, "77", "synthetic-password") } != null)
                f.request()
                check(m.sosSession == null && SosStore.session.value == null && m.page == WorkSafePage.LOGIN)
                runBlocking {
                    val first = async(Dispatchers.Default) { m.login(f.url, "77", "synthetic-password") }
                    check(f.request().line.startsWith("POST /api/worker/auth/login "))
                    check(m.accountBusy)
                    try {
                        val duplicate = withTimeout(5_000) { m.login(f.url, "77", "synthetic-password") }
                        check(duplicate != null && f.requests.poll(300, TimeUnit.MILLISECONDS) == null)
                    } finally {
                        f.respond(200, token)
                        f.respond(200, profile)
                    }
                    check(withTimeout(5_000) { first.await() } == null)
                    check(f.request().line.startsWith("GET /api/worker/me "))
                    check(!m.accountBusy && m.sosSession === SosStore.session.value && m.sosSession != null)
                }
                f.respond(204)
                check(runBlocking { m.logout() } == null)
                f.request()
                runBlocking {
                    val cancelled = async(Dispatchers.Default) { m.login(f.url, "77", "synthetic-password") }
                    check(f.request().line.startsWith("POST /api/worker/auth/login "))
                    check(m.accountBusy)
                    cancelled.cancel()
                    f.respond(200, token)
                    f.respond(204)
                    withTimeout(5_000) { cancelled.join() }
                    check(f.request().line.startsWith("POST /api/worker/auth/logout "))
                    check(!m.accountBusy && m.sosSession == null && SosStore.session.value == null && m.page == WorkSafePage.LOGIN)
                    check(f.requests.poll(300, TimeUnit.MILLISECONDS) == null)
                }
                results.appendLine("PASS: expired session cleared, expired login rejected, duplicate login blocked, cancelled late login not published")
                f.respond(401, "{}")
                check(runBlocking { m.login(f.url, "77", "incorrect") } != null)
                f.request()
                login()
                check(m.profile.workerId == "77" && m.profile.name == "Fixture" && m.profile.gender == "남" && m.profile.age == 30)
                f.respond(500, "{}")
                check(runBlocking { m.refreshProfile() } != null)
                check(f.request().line.startsWith("GET /api/worker/me "))
                check(m.profile.name == "Fixture" && m.profileError != null)
                f.respond(200, profile)
                check(runBlocking { m.refreshProfile() } == null)
                check(f.request().line.startsWith("GET /api/worker/me ") && m.profileError == null)
                check(f.requests.isEmpty())
                f.respond(200, """{"workerId":77,"workerName":"Fixture","sex":null,"age":0,"enabled":true}""")
                check(runBlocking { m.updateProfile("", "", "0") } == null)
                val patch = f.request()
                check(patch.line.startsWith("PATCH /api/worker/me "))
                val body = JSONObject(patch.body)
                check(body.getInt("age") == 0 && !body.has("workerName") && !body.has("sex"))
                check(m.profile.age == 0)
                f.respond(200, """{"workerId":77,"workerName":"Fixture","sex":null,"age":0,"enabled":true}""")
                check(runBlocking { m.refreshProfile() } == null)
                check(f.request().line.startsWith("GET /api/worker/me ") && m.profile.age == 0)
                f.respond(401, "{}")
                check(runBlocking { m.withdraw("wrong") } != null)
                check(f.request().line.startsWith("DELETE /api/worker/me "))
                check(m.sosSession === SosStore.session.value && m.sosSession != null)
                f.respond(0)
                check(runBlocking { m.withdraw("synthetic-password") } != null)
                check(f.request().line.startsWith("DELETE /api/worker/me "))
                Thread.sleep(200); check(f.requests.isEmpty() && m.sosSession != null)
                f.respond(200, "{}")
                check(runBlocking { m.withdraw("synthetic-password") } != null)
                check(f.request().line.startsWith("DELETE /api/worker/me ") && m.sosSession != null)
                f.respond(204)
                check(runBlocking { m.withdraw("synthetic-password") } == null)
                check(f.request().line.startsWith("DELETE /api/worker/me "))
                check(m.page == WorkSafePage.LOGIN && m.sosSession == null)
                login()
                f.respond(200, "{}")
                check(runBlocking { m.changePassword("synthetic-password", "new-synthetic-password", "new-synthetic-password") } != null)
                check(f.request().line.contains("/api/worker/me/password") && m.sosSession != null)
                f.respond(204)
                check(runBlocking { m.changePassword("synthetic-password", "new-synthetic-password", "new-synthetic-password") } == null)
                check(f.request().line.contains("/api/worker/me/password"))
                check(m.page == WorkSafePage.LOGIN && m.sosSession == null)
                login()
                f.respond(204)
                check(runBlocking { m.logout() } == null)
                check(f.request().line.contains("/api/worker/auth/logout"))
                login()
                f.respond(0)
                check(runBlocking { m.logout() } != null)
                f.request()
                check(m.page == WorkSafePage.LOGIN && m.sosSession == null)
                fun await(label: String, condition: () -> Boolean) {
                    val deadline = System.currentTimeMillis() + 8_000
                    while (!condition()) { check(System.currentTimeMillis() < deadline) { label }; Thread.sleep(50) }
                }
                f.respond(200, """{"accessToken":"expiring","tokenType":"Bearer","expiresAt":"${Instant.now().plusSeconds(3)}"}""")
                f.respond(200, profile)
                check(runBlocking { m.login(f.url, "77", "synthetic-password") } == null)
                f.request()
                check(f.request().line.startsWith("GET /api/worker/me "))
                val expiring = checkNotNull(m.sosSession)
                instrumentation.runOnMainSync {
                    SosStore.publish(app, com.example.safetymonitor.sos.SosState(phase = com.example.safetymonitor.sos.SosPhase.COUNTDOWN, incidentId = "expiry-test", workerId = 77))
                }
                await("session expires") { !expiring.valid() }
                check(runBlocking { m.changePassword("old", "new-password", "new-password") } != null)
                check(runBlocking { m.withdraw("old") } != null)
                check(SosStore.session.value === expiring && SosStore.state.value.active)
                instrumentation.runOnMainSync {
                    SosStore.publish(app, SosStore.state.value.copy(phase = com.example.safetymonitor.sos.SosPhase.FAILED))
                }
                await("expired session clears after incident") { SosStore.session.value == null && m.sosSession == null }
                check(m.sessionNotice != null && f.requests.poll(300, TimeUnit.MILLISECONDS) == null)
                login()
                val rejected = checkNotNull(m.sosSession)
                val sending = com.example.safetymonitor.sos.SosState(phase = com.example.safetymonitor.sos.SosPhase.SENDING, incidentId = "401-test", workerId = 77, occurredAt = System.currentTimeMillis())
                instrumentation.runOnMainSync { SosStore.publish(app, sending) }
                f.respond(401, "{}")
                check(com.example.safetymonitor.sos.SosServer.send(rejected, sending).first == com.example.safetymonitor.sos.SosPhase.FAILED)
                check(f.request().line.startsWith("POST /api/worker/events "))
                check(!rejected.valid() && SosStore.session.value === rejected && SosStore.state.value.active)
                instrumentation.runOnMainSync { SosStore.publish(app, sending.copy(phase = com.example.safetymonitor.sos.SosPhase.FAILED)) }
                await("401 session clears after incident") { SosStore.session.value == null && m.sosSession == null }
                check(m.sessionNotice != null)
                results.appendLine("PASS: expiry and SOS 401 retain active incident then clear session after terminal state")
                if (BuildConfig.DEBUG) {
                    check(runBlocking { m.login(f.url, "1024", "0000") } == null)
                    check(m.isDevelopment && m.sosSession?.isDevelopment == true)
                    check(runBlocking { m.updateProfile("Local", "M", "30") } == null)
                    check(m.profile.name == "Local")
                    check(runBlocking { m.changePassword("old", "new-password", "new-password") } != null)
                    check(runBlocking { m.withdraw("old") } != null)
                    instrumentation.runOnMainSync { m.testSos() }
                    val deadline = System.currentTimeMillis() + 25_000
                    while (SosStore.state.value.phase != com.example.safetymonitor.sos.SosPhase.TEST_DONE) {
                        check(System.currentTimeMillis() < deadline) { "dev SOS practice timed out" }
                        Thread.sleep(50)
                    }
                    check(runBlocking { m.logout() } == null)
                    check(f.requests.poll(500, TimeUnit.MILLISECONDS) == null)
                    results.appendLine("PASS: debug 1024/0000 login, local profile, blocked password/delete, SOS practice and logout sent zero worker requests")
                }
                results.appendLine("PASS: loopback login/GET profile, reload failure/retry, shared session, partial age-zero PATCH/readback, delete 401/lost/204, password change, logout 204/lost")
            }
        } finally {
            instrumentation.runOnMainSync { SosStore.publish(app, originalIncident) }
            runBlocking { model?.logout() }
            prefs.edit().apply { if (original == null) remove("sos_server") else putString("sos_server", original) }.commit()
        }
    }
}

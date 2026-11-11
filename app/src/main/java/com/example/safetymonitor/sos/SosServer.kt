package com.example.safetymonitor.sos

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant

// Tokens/passwords are never written to disk or logs. Reconnect after process death.
class SosSession(val server: String, val workerId: Long, val token: String, val expiresAt: Instant) {
    fun valid() = Instant.now().isBefore(expiresAt)
    override fun toString() = "SosSession(workerId=$workerId)"
}

object SosServer {
    fun normalize(raw: String): String {
        val uri = try { URI(raw.trim()) } catch (_: Exception) { throw IllegalArgumentException("서버 주소를 확인하세요.") }
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/") && (uri.port == -1 || uri.port in 1..65535)) {
            "http://주소:포트 또는 https://주소 형식으로 입력하세요."
        }
        return uri.toString().trimEnd('/')
    }

    private fun request(server: String, path: String, body: JSONObject?, token: String? = null): Pair<Int, String> {
        val connection = URI(server + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally { connection.disconnect() }
    }

    fun login(server: String, workerId: Long, password: String): SosSession {
        val (code, body) = request(server, "/api/worker/auth/login",
            JSONObject().put("workerId", workerId).put("password", password))
        require(code == 200) {
            if (code == 401 || code == 403) "작업자 ID 또는 비밀번호를 확인하세요."
            else "서버 로그인 실패 (HTTP $code)"
        }
        val json = JSONObject(body)
        val token = json.getString("accessToken")
        require(token.isNotBlank()) { "서버 인증 응답을 확인하세요." }
        return SosSession(server, workerId, token, Instant.parse(json.getString("expiresAt")))
    }

    fun logout(session: SosSession) { runCatching { request(session.server, "/api/worker/auth/logout", null, session.token) } }

    fun send(session: SosSession, state: SosState): Pair<SosPhase, String> {
        if (!session.valid()) return SosPhase.FAILED to "인증이 만료되어 전송하지 못했습니다. 서버에 다시 로그인하세요."
        return try {
            val (code, body) = request(session.server, "/api/worker/events",
                JSONObject().put("time", Instant.ofEpochMilli(state.occurredAt).toString())
                    .put("eventType", "FALL_DETECTED").put("alertSender", "ANDROID_AUTO_SOS"), session.token)
            when {
                code == 201 -> {
                    val json = JSONObject(body)
                    if (json.getLong("workerId") != session.workerId || json.getString("eventType") != "FALL_DETECTED") {
                        SosPhase.UNKNOWN to "서버 응답의 작업자 또는 이벤트가 일치하지 않습니다. 접수 여부를 확인하세요."
                    } else SosPhase.SENT to "서버 접수 완료 · 이벤트 #${json.getLong("eventId")} · 작업자 ${session.workerId}"
                }
                code in 400..499 -> SosPhase.FAILED to "서버가 요청을 거절했습니다 (HTTP $code). 계정과 서버 설정을 확인하세요."
                else -> SosPhase.UNKNOWN to "서버 응답 오류 (HTTP $code). 관리자 화면에서 접수 여부를 확인하세요."
            }
        } catch (_: Exception) {
            SosPhase.UNKNOWN to "통신이 끊겨 접수 여부를 확인할 수 없습니다. 관리자 화면을 확인하세요. 자동 재전송하지 않습니다."
        }
    }
}

package com.example.safetymonitor.sos

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import com.example.safetymonitor.BuildConfig
import com.example.safetymonitor.network.WorkerApiClient
import com.example.safetymonitor.network.WorkerLoginRequest

// Tokens/passwords are never written to disk or logs. Reconnect after process death.
class SosSession private constructor(
    val server: String, val workerId: Long, val token: String, val expiresAt: Instant,
    val isDevelopment: Boolean
) {
    constructor(server: String, workerId: Long, token: String, expiresAt: Instant) :
        this(server, workerId, token, expiresAt, false)

    @Volatile private var rejected = false
    fun valid() = !rejected && Instant.now().isBefore(expiresAt)
    fun invalidate() { rejected = true }
    override fun toString() = "SosSession(workerId=$workerId)"

    companion object {
        /** A local session carries no server address or access token. Release always authenticates. */
        fun developmentLogin(workerId: Long, password: String): SosSession? =
            if (BuildConfig.DEBUG && workerId == 1024L && password == "0000")
                SosSession("", 1024L, "", Instant.MAX, true)
            else null
    }
}

/** Development admission is local and precedes address validation and network access. */
internal suspend fun loginWorkerSession(server: String, workerId: Long, password: String): SosSession {
    SosSession.developmentLogin(workerId, password)?.let { return it }
    val request = WorkerLoginRequest(workerId, password)
    val address = SosServer.normalize(server)
    val token = WorkerApiClient(address, allowInsecureHttp = address.startsWith("http://")).login(request)
    return SosSession(address, workerId, token.accessToken, token.expiresAt).also {
        require(it.valid()) { "서버 인증이 만료되었습니다. 서버 시간을 확인해주세요." }
    }
}

object SosServer {
    const val DEFAULT_SERVER = "http://34.158.208.112:8081"
    fun configuredAddress(saved: String?): String =
        if (saved.isNullOrBlank() || saved.trim().trimEnd('/') == "http://34.22.75.168:8081") DEFAULT_SERVER else saved

    fun normalize(raw: String): String {
        val uri = try { URI(raw.trim()) } catch (_: Exception) { throw IllegalArgumentException("서버 주소를 확인하세요.") }
        require((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") && (uri.port == -1 || uri.port in 1..65535)) {
            "http://주소:포트 또는 https://주소 형식으로 입력하세요."
        }
        return uri.toString().trimEnd('/')
    }

    private fun request(session: SosSession, body: JSONObject): Pair<Int, String> {
        val connection = URI(session.server + "/api/worker/events").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${session.token}")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (code == 401) session.invalidate()
            val response = if (code == 201) connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } else ""
            return code to response
        } finally { connection.disconnect() }
    }

    fun send(session: SosSession, state: SosState): Pair<SosPhase, String> {
        if (!session.valid()) return SosPhase.FAILED to "인증이 만료되어 전송하지 못했습니다. 서버에 다시 로그인하세요."
        if (session.isDevelopment) return SosPhase.TEST_DONE to "개발 모드 · SOS를 서버로 전송하지 않았습니다."
        return try {
            val (code, body) = request(session,
                JSONObject().put("time", Instant.ofEpochMilli(state.occurredAt).toString())
                    .put("eventType", "FALL_DETECTED").put("alertSender", "낙상 SOS"))
            when {
                code == 201 -> {
                    val json = JSONObject(body)
                    if (json.getLong("workerId") != session.workerId || json.getString("eventType") != "FALL_DETECTED") {
                        SosPhase.UNKNOWN to "서버 응답의 작업자 또는 이벤트가 일치하지 않습니다. 접수 여부를 확인하세요."
                    } else SosPhase.SENT to "서버 접수 완료 · 이벤트 #${json.getLong("eventId")} · 작업자 ${session.workerId}"
                }
                code == 401 -> SosPhase.FAILED to "서버 인증이 만료되거나 해제되었습니다. 다시 로그인해주세요."
                code in 400..499 -> SosPhase.FAILED to "서버가 요청을 거절했습니다 (HTTP $code). 계정과 서버 설정을 확인하세요."
                else -> SosPhase.UNKNOWN to "서버 응답 오류 (HTTP $code). 관리자 화면에서 접수 여부를 확인하세요."
            }
        } catch (_: Exception) {
            SosPhase.UNKNOWN to "통신이 끊겨 접수 여부를 확인할 수 없습니다. 관리자 화면을 확인하세요. 자동 재전송하지 않습니다."
        }
    }
}

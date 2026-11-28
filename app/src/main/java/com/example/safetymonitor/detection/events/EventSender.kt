package com.example.safetymonitor.detection.events

import com.example.safetymonitor.network.*
import com.example.safetymonitor.sos.SosSession
import java.io.IOException
import java.time.Instant

internal suspend fun deliverEvent(session: SosSession, event: DetectedEvent, at: Instant,
    isCurrent: () -> Boolean, onAccepted: () -> Unit = {},
    send: suspend (String, WorkerEventRequest) -> WorkerEventInfo): String {
    if (!isCurrent() || !session.valid()) return "전송 안 됨 · 로그인 만료"
    if (session.isDevelopment) return "개발 모드 · 서버 전송 없음"
    val request = WorkerEventRequest(at, event.type, event.sender)
    return try {
        val result = send(session.token, request)
        val matchingSender = result.alertSender == event.sender ||
            (event.type == WorkerEventType.ABNORMAL_VITAL && event.sender == HEART_RATE_ALERT_SENDER &&
                result.alertSender == LEGACY_HEART_RATE_ALERT_SENDER)
        if (result.workerId != session.workerId || result.eventType != event.type || !matchingSender)
            "응답 불일치 · 관리자에게 접수 확인 필요"
        else {
            onAccepted()
            "서버 접수 완료 · #${result.eventId}"
        }
    } catch (e: WorkerApiException) {
        if (e.httpStatus == 401) session.invalidate()
        if (e.httpStatus in 400..499) "전송 거절 · HTTP ${e.httpStatus}"
        else "접수 여부 불명 · 자동 재전송 없음"
    } catch (_: IOException) { "통신 오류 · 접수 여부 불명 · 자동 재전송 없음" }
}

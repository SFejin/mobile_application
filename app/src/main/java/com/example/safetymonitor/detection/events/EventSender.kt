package com.example.safetymonitor.detection.events

import com.example.safetymonitor.network.*
import com.example.safetymonitor.sos.SosSession
import java.io.IOException
import java.time.Instant

/** No retry: an interrupted or malformed POST response can still mean the event was stored. */
internal suspend fun deliverEvent(
    session: SosSession, event: DetectedEvent, occurredAt: Instant,
    isCurrent: () -> Boolean,
    send: suspend (String, WorkerEventRequest) -> WorkerEventInfo
): String {
    if (!isCurrent() || !session.valid()) return "전송 안 됨 · 인증 만료 또는 계정 변경"
    if (session.isDevelopment) return "개발 모드 · 서버 전송 없음"
    return try {
        val response = send(session.token, WorkerEventRequest(occurredAt, event.type, event.sender))
        if (response.workerId == session.workerId && response.eventType == event.type && response.eventId > 0) {
            "서버 접수 완료 · 이벤트 #${response.eventId}"
        } else "접수 여부 불명 · 서버 응답 불일치"
    } catch (e: WorkerApiException) {
        if (e.httpStatus == 401) session.invalidate()
        if (e.httpStatus in 400..499) "서버 거절 · HTTP ${e.httpStatus}"
        else "접수 여부 불명 · HTTP ${e.httpStatus}"
    } catch (_: IOException) {
        "접수 여부 불명 · 통신 오류, 자동 재전송 없음"
    }
}

package com.example.safetymonitor.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.example.safetymonitor.detection.events.*
import com.example.safetymonitor.detection.fall.FallMonitoringStore

@Composable
fun EventAlertsCard() {
    val config by EventSettings.state.collectAsState()
    val records by WorkerEventStore.state.collectAsState()
    val monitoring by FallMonitoringStore.state.collectAsState()
    val checks by SafetyCheckStore.state.collectAsState()
    val limits = requireNotNull(config.heart)
    WorkSafeCard {
        Text("심박·위험 비콘 자동 알림")
        Text(if (monitoring.motion.monitoring) "감시 중" else "감시 중지 · 센서 측정 상태를 확인하세요")
        Text("심박 확인 기준: ${limits.low.toInt()} bpm 미만 또는 ${limits.high.toInt()} bpm 이상")
        Text("첫 이상 구간에서 상태를 확인합니다. 30초간 무응답이거나 다음 구간에도 같은 방향의 이상이 이어지면 SOS를 전송합니다.")
        Text("심박은 5/10분 평균값입니다. 순간 이상을 즉시 감지하지 않으며 이 기준은 개발용으로, 의학적 진단 기준이 아닙니다.")
        Text("손목 피부온도·주변온도는 참고값으로 표시하며 자동 알림 판단에는 사용하지 않습니다.")
        Text("위험 비콘 접근 확인에 30초간 답이 없으면 관리자에게 알립니다. 작업 종료 확인에 30초간 답이 없으면 SOS를 전송합니다.")
        checks.zones.filter { it.stage == ZoneStage.WORKING }.forEach { zone -> key(zone.id) { ZoneWorkStatus(zone) } }
        Text("최근 자동 알림 전송 기록")
        if (records.isEmpty()) Text("기록 없음")
        records.take(5).forEach { Text("${it.event.description}\n${it.at} · ${it.status}") }
    }
}

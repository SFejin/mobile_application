package com.example.safetymonitor.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.example.safetymonitor.detection.events.*
import com.example.safetymonitor.detection.fall.FallMonitoringStore

@Composable
fun EventAlertsCard() {
    val config by EventSettings.state.collectAsState()
    val skinStatus by WorkerEventStore.skinStatus.collectAsState()
    val records by WorkerEventStore.state.collectAsState()
    val monitoring by FallMonitoringStore.state.collectAsState()
    val checks by SafetyCheckStore.state.collectAsState()
    val statusReport by com.example.safetymonitor.network.WorkerStatusReporter.state.collectAsState()
    val limits = requireNotNull(config.heart)
    WorkSafeCard {
        Text("생체 신호·위험 비콘 자동 알림")
        Text(if (monitoring.motion.monitoring) "감시 중" else "감시 중지 · 센서 측정 상태를 확인하세요")
        Text("심박 확인 기준: ${limits.low.toInt()} bpm 미만 또는 ${limits.high.toInt()} bpm 초과")
        Text("같은 방향의 이상이 연속 두 구간에서 확인되면 위험 상태와 이벤트를 전송합니다. 첫 이상 구간만으로는 위험을 확정하지 않습니다.")
        Text("심박은 5분 평균값입니다. 순간 이상을 즉시 감지하지 않으며 이 기준은 개발용으로, 의학적 진단 기준이 아닙니다.")
        Text(statusReport)
        Text(skinStatus)
        Text("손목 온도: 주변온도에 맞춰 6개 연속 구간(약 30분)의 안정된 측정값으로 개인 기준선을 설정합니다. 환경 변경 시 다시 준비합니다. 준비 중에는 온도 이상을 판단하지 않습니다.")
        Text("기준선보다 2°C 이상 상승 또는 하락한 값이 같은 방향으로 연속 두 구간에서 나오면 알림을 전송합니다. 연구용 설정이며 발열·열사병·저체온증 진단 기준이 아닙니다.")
        Text("위험 비콘 접근 확인에 30초간 답이 없으면 관리자에게 알립니다. 작업 종료 확인에 30초간 답이 없으면 SOS를 전송합니다.")
        checks.zones.filter { it.stage == ZoneStage.WORKING }.forEach { zone -> key(zone.id) { ZoneWorkStatus(zone) } }
        Text("최근 자동 알림 전송 기록")
        if (records.isEmpty()) Text("기록 없음")
        records.take(5).forEach { Text("${it.event.description}\n${it.at} · ${it.status}") }
    }
}

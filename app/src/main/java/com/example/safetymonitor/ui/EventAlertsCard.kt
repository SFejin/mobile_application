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
    val limits = requireNotNull(config.heart)
    WorkSafeCard {
        Text("심박·위험 비콘 자동 알림")
        Text(if (monitoring.motion.monitoring) "감시 중" else "감시 중지 · 센서 측정 상태를 확인하세요")
        Text("심박 고정 기준: ${limits.low.toInt()} bpm 미만 또는 ${limits.high.toInt()} bpm 초과")
        Text("같은 방향으로 기준을 벗어난 연속 2개 측정 구간에서 자동 전송합니다. 50·120 bpm은 범위 안으로 처리합니다.")
        Text("심박은 5/10분 평균값입니다. 순간 이상을 즉시 감지하지 않으며 이 기준은 개발용으로, 의학적 진단 기준이 아닙니다.")
        Text("손목 피부온도·주변온도는 참고값으로 표시하며 자동 알림 판단에는 사용하지 않습니다.")
        Text("위험 비콘: 추정 2m 이내에서 3초 이상 접근 시 전송합니다. 3m 이상 이탈을 확인한 후 재감지합니다.")
        Text("최근 자동 알림 전송 기록")
        if (records.isEmpty()) Text("기록 없음")
        records.take(5).forEach { Text("${it.event.description}\n${it.at} · ${it.status}") }
    }
}

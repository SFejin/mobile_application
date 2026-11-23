package com.example.safetymonitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.safetymonitor.detection.events.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DetectionSettingsScreen(config: EventConfig, records: List<WorkerEventRecord>, busy: Boolean,
    onSave: (String, Boolean, String, String) -> String?) {
    var ids by rememberSaveable(config) { mutableStateOf(config.dangerIds.sorted().joinToString(", ")) }
    var heart by rememberSaveable(config) { mutableStateOf(config.heartEnabled) }
    var low by rememberSaveable(config) { mutableStateOf(config.lowHeartRate.toString()) }
    var high by rememberSaveable(config) { mutableStateOf(config.highHeartRate.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WorkSafeCard {
            Text("위험구역 접근", style = MaterialTheme.typography.titleMedium)
            WorkSafeField("위험 비콘 ID", ids, { ids = it }, enabled = !busy,
                placeholder = "예: 1, 2, 10 · 미입력 시 감지 안 함")
            Text("등록한 비콘의 추정 거리 2m 이내에서 3초 이상, 서로 다른 수신 3회 이상이면 전송합니다. 3m 이상 이탈을 확인한 뒤 다시 감지합니다.",
                style = MaterialTheme.typography.bodySmall)
            Text("BLE 거리는 환경에 따라 달라집니다. 실제 위험 경계에 맞춰 비콘을 배치하고 확인하세요.", style = MaterialTheme.typography.bodySmall)
        }
        WorkSafeCard {
            Text("생체신호 이상 · 평균 심박", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("평균 심박 감지")
                Switch(checked = heart, onCheckedChange = { heart = it }, enabled = !busy)
            }
            WorkSafeField("심박 하한 (bpm)", low, { low = it }, keyboardType = KeyboardType.Number, enabled = !busy)
            WorkSafeField("심박 상한 (bpm)", high, { high = it }, keyboardType = KeyboardType.Number, enabled = !busy)
            Text("같은 방향으로 하한 이하 또는 상한 이상인 평균값이 2구간 연속 수신되면 전송합니다. 현재 워치는 5분 또는 10분 요약을 보냅니다.", style = MaterialTheme.typography.bodySmall)
            Text("기본 50/120은 개발용 기준이며 의학적 진단 기준이 아닙니다. 활동량과 개인 차이를 고려해 설정하세요. 피부온도는 판정에 사용하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it) }
            WorkSafeButton("감지 설정 저장", onClick = {
                message = onSave(ids, heart, low, high) ?: "감지 설정을 저장했습니다."
            }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Text("이 기기에 저장됩니다. 기준을 바꾸면 진행 중인 판정을 초기화합니다.", style = MaterialTheme.typography.bodySmall)
        }
        WorkSafeCard {
            Text("최근 감지 이벤트", style = MaterialTheme.typography.titleMedium)
            Text("현재 로그인 세션의 최근 20건 · 서버 거절이나 통신 오류는 자동 재전송하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            if (records.isEmpty()) Text("아직 감지된 이벤트가 없습니다.")
            records.forEach { record ->
                HorizontalDivider()
                Text(record.event.description)
                Text(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(record.at),
                    style = MaterialTheme.typography.bodySmall)
                Text(record.status, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

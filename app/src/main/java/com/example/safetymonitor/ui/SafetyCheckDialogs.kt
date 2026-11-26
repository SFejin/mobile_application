package com.example.safetymonitor.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.safetymonitor.detection.events.*
import kotlinx.coroutines.delay

@Composable
internal fun SafetyCheckDialogs(checks: SafetyChecks) {
    val overdue = checks.zones.firstOrNull { it.stage == ZoneStage.OVERDUE }
    val heart = checks.heart
    val approach = checks.zones.firstOrNull { it.stage == ZoneStage.APPROACH }
    when {
        overdue != null -> key(overdue.id) { OverdueDialog(overdue) }
        heart != null -> HeartDialog(heart)
        approach != null -> key(approach.id) { ApproachDialog(approach) }
    }
}

@Composable
private fun HeartDialog(check: HeartCheck) {
    val seconds = remainingSeconds(check.deadline)
    AlertDialog(
        onDismissRequest = {},
        title = { Text("심박수 상태 확인") },
        text = { Text("${check.event.description}\n${seconds}초 안에 응답이 없으면 SOS를 전송합니다. 괜찮아요를 눌러도 다음 측정 구간을 확인합니다.") },
        confirmButton = { TextButton(onClick = { SafetyCheckStore.respond(check.id, CheckAction.OK) }) { Text("괜찮아요") } },
        dismissButton = { TextButton(onClick = { SafetyCheckStore.respond(check.id, CheckAction.HELP) }) { Text("지금 SOS 보내기") } }
    )
}

@Composable
private fun ApproachDialog(zone: ZoneCheck) {
    var minutes by rememberSaveable { mutableIntStateOf(5) }
    val seconds = remainingSeconds(zone.deadline)
    AlertDialog(
        onDismissRequest = {},
        title = { Text("위험 비콘 #${zone.beaconId} 접근") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (zone.nearby) "작업할 경우 시간을 선택하세요." else "작업을 시작하려면 비콘에 가까이 이동하세요.")
                Text("${seconds}초 안에 응답이 없으면 관리자에게 알립니다.")
                workMinutes.forEach { option ->
                    Row(Modifier.fillMaxWidth().selectable(minutes == option, role = Role.RadioButton) { minutes = option },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = minutes == option, onClick = null)
                        Text("${option}분")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = zone.nearby, onClick = { SafetyCheckStore.respond(zone.id, CheckAction.START, minutes) }) {
                Text("작업 시작")
            }
        },
        dismissButton = { TextButton(onClick = { SafetyCheckStore.respond(zone.id, CheckAction.PASS) }) { Text("통과") } }
    )
}

@Composable
private fun OverdueDialog(zone: ZoneCheck) {
    val seconds = remainingSeconds(zone.deadline)
    AlertDialog(
        onDismissRequest = {},
        title = { Text("위험 비콘 #${zone.beaconId} · 작업이 끝났나요?") },
        text = { Text("${seconds}초 안에 응답이 없으면 SOS를 전송합니다.") },
        confirmButton = { TextButton(onClick = { SafetyCheckStore.respond(zone.id, CheckAction.FINISH) }) { Text("작업 끝") } },
        dismissButton = { TextButton(onClick = { SafetyCheckStore.respond(zone.id, CheckAction.EXTEND) }) { Text("5분 연장") } }
    )
}

@Composable
internal fun ZoneWorkStatus(zone: ZoneCheck) {
    val seconds = remainingSeconds(zone.deadline)
    Text("위험 비콘 #${zone.beaconId} 작업 중 · ${seconds / 60}분 ${seconds % 60}초 남음")
    if (!zone.nearby) Text("작업 끝을 누르려면 비콘에 가까이 이동하세요.")
    WorkSafeButton("작업 끝", onClick = { SafetyCheckStore.respond(zone.id, CheckAction.FINISH) }, enabled = zone.nearby)
}

@Composable
private fun remainingSeconds(deadline: Long): Long {
    fun remaining() = ((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
    val seconds by produceState(remaining(), deadline) {
        while (true) {
            value = remaining()
            if (value == 0L) break
            delay(250)
        }
    }
    return seconds
}

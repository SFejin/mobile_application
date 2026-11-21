package com.example.safetymonitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import com.example.safetymonitor.sos.*

@Composable
fun SosCountdownDialog(model: WorkSafeViewModel) {
    val state = model.sos
    var dismissed by rememberSaveable { mutableStateOf("") }
    val session = model.sosSession
    val sessionId = session?.workerId
    if (state.workerId != sessionId || sessionId == null) return
    // A real account with ID 1024 may have a prior server result; do not present it as a dev result.
    if (session?.isDevelopment == true && !state.test) return
    if (state.phase != SosPhase.COUNTDOWN) {
        if (state.phase in setOf(SosPhase.SENT, SosPhase.FAILED, SosPhase.UNKNOWN) &&
            dismissed != state.incidentId) {
            AlertDialog(onDismissRequest = { dismissed = state.incidentId },
                title = { Text(if (state.phase == SosPhase.SENT) "SOS 서버 접수 완료" else "SOS 전송 상태 확인") },
                text = { Text(state.message) },
                confirmButton = { TextButton(onClick = { dismissed = state.incidentId }) { Text("확인") } })
        }
        return
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (state.test) "SOS 알람 연습" else "낙상이 감지되었습니다") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${state.remainingSeconds}초", style = MaterialTheme.typography.displayMedium)
                Text(if (state.test) "연습입니다. 서버로 전송하지 않습니다."
                    else if (state.workerId == null) "서버 미연결 상태입니다. 관리자에게 직접 도움을 요청하세요."
                    else "취소하지 않으면 작업자 ${state.workerId}의 낙상 SOS를 서버로 전송합니다.")
                Text("괜찮다면 아래 버튼을 눌러 취소하세요.")
            }
        },
        confirmButton = { TextButton(onClick = { model.cancelSos(state.incidentId) }) { Text("괜찮아요 · 취소") } }
    )
}

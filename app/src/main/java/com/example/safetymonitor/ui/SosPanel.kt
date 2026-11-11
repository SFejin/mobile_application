package com.example.safetymonitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.safetymonitor.sos.*

@Composable
fun SosPanel(model: WorkSafeViewModel) {
    var settings by remember { mutableStateOf(false) }
    val state = model.sos
    val session = model.sosSession
    WorkSafeCard {
        Text("자동 SOS", style = MaterialTheme.typography.titleMedium)
        Text(if (session == null) "서버 미연결 · 휴대폰 알람만 가능"
            else if (!session.valid()) "서버 인증 만료 · 다시 연결하세요"
            else "서버 인증됨 · 전송 계정 ID ${session.workerId}")
        session?.let { Text(it.server, style = MaterialTheme.typography.bodySmall) }
        Text(state.message, color = if (state.phase in setOf(SosPhase.FAILED, SosPhase.UNKNOWN))
            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (!model.sosNotificationsEnabled) {
            Text("알림 권한이 꺼져 있습니다. 백그라운드 취소 알림을 받으려면 시스템 설정에서 알림을 허용하세요.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.incidentId.isNotEmpty()) {
            Text(if (state.test) "최근 알람: 연습" else "최근 알람 전송 대상: ${state.workerId?.let { "작업자 $it" } ?: "연결 없음"}",
                style = MaterialTheme.typography.bodySmall)
        }
        WorkSafeButton("SOS 서버 연결", { settings = true }, enabled = !state.active && !model.sosConnecting)
        if (model.sosTestAvailable) {
            WorkSafeButton("알람 연습 · 서버 전송 없음", model::testSos,
                secondary = true, enabled = !state.active)
        }
        Text("앱 데모 계정과 별도로 위 서버 계정에 전송합니다. 앱 프로세스가 종료되면 다시 연결하세요.",
            style = MaterialTheme.typography.bodySmall)
    }
    if (settings) {
        var url by remember { mutableStateOf(model.sosServerAddress) }
        var worker by remember { mutableStateOf(session?.workerId?.toString().orEmpty()) }
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!model.sosConnecting) settings = false },
            title = { Text("SOS 서버 연결") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("관리자가 발급한 실제 서버 작업자 계정을 입력하세요.")
                    WorkSafeField("서버 주소", url, { url = it }, keyboardType = KeyboardType.Uri)
                    WorkSafeField("작업자 ID", worker, { worker = it }, keyboardType = KeyboardType.Number)
                    WorkSafeField("비밀번호", password, { password = it }, password = true)
                    model.sosConnectionMessage?.let { Text(it) }
                    if (url.startsWith("http://")) Text("현재 HTTP 연결은 암호화되지 않습니다.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = !model.sosConnecting && !state.active, onClick = {
                    model.connectSos(url, worker, password)
                    password = ""
                }) { Text(if (model.sosConnecting) "연결 중…" else "연결") }
            },
            dismissButton = { TextButton(enabled = !model.sosConnecting, onClick = { settings = false }) { Text("닫기") } }
        )
    }
}

@Composable
fun SosCountdownDialog(model: WorkSafeViewModel) {
    val state = model.sos
    if (state.phase != SosPhase.COUNTDOWN) return
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

package com.example.safetymonitor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.DialogProperties
import com.example.safetymonitor.sos.ReceiptAlarmStore

@Composable
internal fun ReceiptAlarmDialog(id: String) {
    val reason by ReceiptAlarmStore.reason.collectAsState()
    var code by rememberSaveable(id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("$reason · 알림음 해제") },
        text = {
            Column {
                Text("알림음 해제 코드: 0000")
                Text("SOS 알림음만 종료합니다. 전송된 SOS와 서버 기록은 유지됩니다.")
                OutlinedTextField(
                    value = code,
                    onValueChange = { value -> code = value.filter { it in '0'..'9' }.take(4) },
                    label = { Text("해제 코드 4자리") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = code.length == 4 && code != "0000"
                )
                if (code.length == 4 && code != "0000") Text("해제 코드 0000을 입력하세요.")
            }
        },
        confirmButton = {
            TextButton(enabled = code == "0000", onClick = { ReceiptAlarmStore.silence(id, code) }) {
                Text("알림음 끄기")
            }
        }
    )
}

package com.example.safetymonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.safetymonitor.sos.SosServer

@Composable
fun ServerAddressDialog(
    currentAddress: String,
    onDismiss: () -> Unit,
    onSave: (String) -> String?
) {
    var address by rememberSaveable(currentAddress) { mutableStateOf(currentAddress) }
    var error by remember { mutableStateOf<String?>(null) }
    val save = { error = onSave(address) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("서버 연결 설정") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("변경한 주소는 다음 로그인부터 사용됩니다.")
                WorkSafeField(
                    label = "서버 주소", value = address,
                    onValueChange = { address = it; error = null },
                    placeholder = "https://서버주소 또는 http://주소:포트",
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done,
                    onDone = save, outlined = true, isError = error != null,
                    supportingText = error
                )
                if (address.trim().startsWith("http://", ignoreCase = true)) {
                    Text("HTTP 연결은 암호화되지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { address = SosServer.DEFAULT_SERVER; error = null }) {
                    Text("기본 주소로 되돌리기")
                }
            }
        },
        confirmButton = { TextButton(onClick = save) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

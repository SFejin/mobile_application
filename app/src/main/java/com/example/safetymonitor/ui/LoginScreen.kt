package com.example.safetymonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    onLogin: (String, String) -> Unit,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    var workerId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        WorkSafeCard {
            Text("작업자 로그인", style = MaterialTheme.typography.titleLarge)
            Text(
                "관리자가 발급한 작업자 ID로 로그인",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        WorkSafeField(
            label = "작업자 ID",
            value = workerId,
            onValueChange = { workerId = it },
            placeholder = "숫자 ID 입력",
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
            outlined = true
        )
        WorkSafeField(
            label = "비밀번호",
            value = password,
            onValueChange = { password = it },
            placeholder = "비밀번호 입력",
            password = true,
            imeAction = ImeAction.Done,
            onDone = { onLogin(workerId, password) },
            outlined = true
        )

        if (errorMessage != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.padding(12.dp).semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        WorkSafeButton(
            text = "로그인",
            onClick = { onLogin(workerId, password) },
            modifier = Modifier.fillMaxWidth(),
            emphasized = true
        )

        WorkSafeCard(muted = true) {
            Text(
                "개발용 데모 · 실제 인증 아님",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "데모 계정 ID 1024 · 초기 비밀번호 0000",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "계정·프로필·변경 비밀번호는 앱을 완전히 종료하고 다시 실행하면 초기화됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

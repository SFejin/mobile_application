package com.example.safetymonitor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LoginScreen(
    onLogin: (String, String) -> Unit,
    busy: Boolean,
    onServerSettings: () -> Unit,
    errorMessage: String?,
    noticeMessage: String? = null,
    modifier: Modifier = Modifier
) {
    var workerId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val submit = {
        if (!busy) {
            val submitted = password
            password = ""
            onLogin(workerId, submitted)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        WorkSafeCard {
            Text("작업자 로그인", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    enabled = !busy,
                    onClick = {},
                    onLongClickLabel = "서버 연결 설정",
                    onLongClick = onServerSettings
                ).padding(vertical = 8.dp))
            Text(
                "관리자가 발급한 작업자 ID와 비밀번호로 로그인",
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
            outlined = true,
            enabled = !busy
        )
        WorkSafeField(
            label = "비밀번호",
            value = password,
            onValueChange = { password = it },
            placeholder = "비밀번호 입력",
            password = true,
            imeAction = ImeAction.Done,
            onDone = submit,
            outlined = true,
            enabled = !busy
        )

        noticeMessage?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        errorMessage?.let {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = it,
                    modifier = Modifier.padding(12.dp).semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        WorkSafeButton(
            text = if (busy) "로그인 중…" else "로그인",
            enabled = !busy,
            onClick = submit,
            modifier = Modifier.fillMaxWidth(),
            emphasized = true
        )

        Text(
            "서버에 등록된 계정으로 로그인합니다. 비밀번호를 잊었다면 관리자에게 재발급을 요청해주세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (com.example.safetymonitor.BuildConfig.DEBUG) {
            Text(
                "개발용: ID 1024 / 비밀번호 0000으로 서버 없이 시작할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

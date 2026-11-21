package com.example.safetymonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun PersonalInfoScreen(
    profile: WorkerProfile,
    onSave: (String, String, String) -> Unit,
    busy: Boolean,
    isDevelopment: Boolean,
    modifier: Modifier = Modifier
) {
    var name by rememberSaveable(profile.name) { mutableStateOf(profile.name) }
    var gender by rememberSaveable(profile.gender) { mutableStateOf(profile.gender) }
    var age by rememberSaveable(profile.age) { mutableStateOf(profile.age?.toString().orEmpty()) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        WorkSafeCard {
            Text("개인 정보 변경", style = MaterialTheme.typography.titleMedium)
            Text(
                if (isDevelopment) {
                    "개발 모드: 변경 내용은 이 앱 안에만 임시 반영되며 서버로 전송되지 않습니다."
                } else {
                    "빈 항목은 변경하지 않습니다. 성별: M(남), F(여), U(미상); 나이: 0–150"
                },
                style = MaterialTheme.typography.bodySmall
            )
            WorkSafeField(label = "이름", value = name, onValueChange = { name = it }, enabled = !busy)
            WorkSafeField(
                label = "성별 (M/F/U)",
                value = gender,
                onValueChange = { gender = it },
                enabled = !busy
            )
            WorkSafeField(
                label = "나이",
                value = age,
                onValueChange = { age = it },
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                onDone = { if (!busy) onSave(name, gender, age) },
                enabled = !busy
            )
            WorkSafeButton(
                text = if (busy) "저장 중…" else "변경사항 저장",
                onClick = { if (!busy) onSave(name, gender, age) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            )
        }
    }
}

@Composable
fun ThemeSettingsScreen(
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        WorkSafeCard {
            Text("화면 모드 변경", style = MaterialTheme.typography.titleMedium)
            Text(
                "앱 화면 테마를 선택합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = darkTheme, role = Role.Switch, onValueChange = onThemeChange)
                    .semantics { contentDescription = "다크 모드" }
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (darkTheme) "Dark mode" else "Light mode",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(checked = darkTheme, onCheckedChange = null)
            }
        }
    }
}

@Composable
fun PasswordSettingsScreen(
    onSave: (String, String, String) -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val submit = {
        if (!busy) {
            val current = currentPassword
            val new = newPassword
            val confirm = confirmation
            currentPassword = ""
            newPassword = ""
            confirmation = ""
            onSave(current, new, confirm)
        }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        WorkSafeCard {
            Text("비밀번호 변경", style = MaterialTheme.typography.titleMedium)
            Text(
                "새 비밀번호는 12–64자, UTF-8 기준 72바이트 이하여야 합니다.",
                style = MaterialTheme.typography.bodySmall
            )
            WorkSafeField(
                label = "현재 비밀번호",
                value = currentPassword,
                onValueChange = { currentPassword = it },
                password = true,
                enabled = !busy
            )
            WorkSafeField(
                label = "새 비밀번호",
                value = newPassword,
                onValueChange = { newPassword = it },
                placeholder = "새 비밀번호 입력",
                password = true,
                enabled = !busy
            )
            WorkSafeField(
                label = "새 비밀번호 확인",
                value = confirmation,
                onValueChange = { confirmation = it },
                placeholder = "다시 입력",
                password = true,
                imeAction = ImeAction.Done,
                onDone = submit,
                enabled = !busy
            )
            WorkSafeButton(
                text = if (busy) "변경 중…" else "비밀번호 변경",
                onClick = submit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            )
        }
    }
}

@Composable
fun WithdrawScreen(
    server: String,
    workerId: String,
    busy: Boolean,
    onWithdraw: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var password by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        WorkSafeCard {
            Text(
                "회원 탈퇴",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                "탈퇴하면 서버 계정이 비활성화되고 이름·성별·나이가 익명화됩니다. 기존 안전 이벤트 기록은 보존되며, 이 계정으로 다시 로그인할 수 없습니다.",
                style = MaterialTheme.typography.bodySmall
            )
            WorkSafeField(
                label = "현재 비밀번호",
                value = password,
                onValueChange = { password = it },
                password = true,
                enabled = !busy,
                imeAction = ImeAction.Done,
                onDone = { if (!busy && password.isNotBlank()) confirming = true }
            )
            WorkSafeButton(
                text = if (busy) "탈퇴 처리 중…" else "회원 탈퇴",
                onClick = { if (!busy) confirming = true },
                enabled = !busy && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                danger = true
            )
        }
    }
    if (confirming) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    confirming = false
                    password = ""
                }
            },
            title = { Text("회원 탈퇴 최종 확인") },
            text = {
                Text(
                    "서버: $server\n작업자 ID: $workerId\n\n계정이 비활성화되고 프로필이 익명화됩니다. 이 작업은 되돌릴 수 없습니다."
                )
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    enabled = !busy,
                    onClick = {
                        confirming = false
                        password = ""
                    }
                ) { Text("취소") }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = !busy,
                    onClick = {
                        val submitted = password
                        password = ""
                        confirming = false
                        onWithdraw(submitted)
                    }
                ) {
                    Text("탈퇴", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

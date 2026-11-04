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
    modifier: Modifier = Modifier
) {
    var name by rememberSaveable(profile.name) { mutableStateOf(profile.name) }
    var gender by rememberSaveable(profile.gender) { mutableStateOf(profile.gender) }
    var age by rememberSaveable(profile.age) { mutableStateOf(profile.age.toString()) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        WorkSafeCard {
            Text("개인 정보 변경", style = MaterialTheme.typography.titleMedium)
            WorkSafeField(label = "이름", value = name, onValueChange = { name = it })
            WorkSafeField(label = "성별", value = gender, onValueChange = { gender = it })
            WorkSafeField(
                label = "나이",
                value = age,
                onValueChange = { age = it },
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                onDone = { onSave(name, gender, age) }
            )
            WorkSafeButton(
                text = "변경사항 저장",
                onClick = { onSave(name, gender, age) },
                modifier = Modifier.fillMaxWidth()
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
    modifier: Modifier = Modifier
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        WorkSafeCard {
            Text("비밀번호 변경", style = MaterialTheme.typography.titleMedium)
            WorkSafeField(
                label = "현재 비밀번호",
                value = currentPassword,
                onValueChange = { currentPassword = it },
                password = true
            )
            WorkSafeField(
                label = "새 비밀번호",
                value = newPassword,
                onValueChange = { newPassword = it },
                placeholder = "새 비밀번호 입력",
                password = true
            )
            WorkSafeField(
                label = "새 비밀번호 확인",
                value = confirmation,
                onValueChange = { confirmation = it },
                placeholder = "다시 입력",
                password = true,
                imeAction = ImeAction.Done,
                onDone = { onSave(currentPassword, newPassword, confirmation) }
            )
            WorkSafeButton(
                text = "비밀번호 변경",
                onClick = { onSave(currentPassword, newPassword, confirmation) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun WithdrawScreen(onWithdraw: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        WorkSafeCard {
            Text(
                "회원 탈퇴",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                "로컬 데모 계정이 현재 앱 프로세스에서 비활성화됩니다. 앱을 완전히 종료한 뒤 다시 실행하면 초기 데모 계정으로 복원됩니다. 실제 서버의 계정은 변경하지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WorkSafeButton(
                text = "회원 탈퇴",
                onClick = onWithdraw,
                modifier = Modifier.fillMaxWidth(),
                danger = true
            )
        }
    }
}

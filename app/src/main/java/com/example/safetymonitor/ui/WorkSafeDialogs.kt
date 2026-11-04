package com.example.safetymonitor.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun SettingsDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSelect: (WorkSafePage) -> Unit
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        Box {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = onDismiss,
                modifier = Modifier.widthIn(min = 180.dp),
                shape = RoundedCornerShape(5.dp),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                DropdownMenuItem(
                    text = { Text("개인 정보 변경") },
                    onClick = { onDismiss(); onSelect(WorkSafePage.PERSONAL_INFO) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                DropdownMenuItem(
                    text = { Text("화면 모드 변경") },
                    onClick = { onDismiss(); onSelect(WorkSafePage.THEME) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                DropdownMenuItem(
                    text = { Text("비밀번호 변경") },
                    onClick = { onDismiss(); onSelect(WorkSafePage.PASSWORD) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                DropdownMenuItem(
                    text = { Text("회원 탈퇴", color = MaterialTheme.colorScheme.error) },
                    onClick = { onDismiss(); onSelect(WorkSafePage.WITHDRAW) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
    }
}

@Composable
fun WorkSafeDialog(
    state: WorkSafeDialogState,
    onDismiss: () -> Unit,
    onConfirmWithdrawal: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                modifier = Modifier.padding(top = maxHeight * 0.30f)
                    .widthIn(max = 300.dp).fillMaxWidth()
                    .heightIn(max = maxHeight * 0.65f)
                    .pointerInput(Unit) { detectTapGestures { /* Keep taps inside the modal. */ } },
                shape = RoundedCornerShape(5.dp),
                color = colors.surface,
                contentColor = colors.onSurface
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = when (state.kind) {
                            WorkSafeDialogKind.CONFIRM_WITHDRAWAL -> "작업을 실행할까요?"
                            WorkSafeDialogKind.SUCCESS -> "✓  작업 완료"
                            WorkSafeDialogKind.FAILURE -> "!  작업 실패"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = when (state.kind) {
                            WorkSafeDialogKind.CONFIRM_WITHDRAWAL -> colors.onSurface
                            WorkSafeDialogKind.SUCCESS -> colors.tertiary
                            WorkSafeDialogKind.FAILURE -> colors.error
                        }
                    )
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    when (state.kind) {
                        WorkSafeDialogKind.CONFIRM_WITHDRAWAL -> Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            WorkSafeButton(
                                text = "취소",
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                                secondary = true
                            )
                            WorkSafeButton(
                                text = "확인",
                                onClick = onConfirmWithdrawal,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        WorkSafeDialogKind.SUCCESS -> WorkSafeButton(text = "확인", onClick = onDismiss)
                        WorkSafeDialogKind.FAILURE -> WorkSafeButton(
                            text = "닫기", onClick = onDismiss, secondary = true
                        )
                    }
                }
            }
        }
    }
}

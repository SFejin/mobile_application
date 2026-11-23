package com.example.safetymonitor.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.safetymonitor.detection.beacon.BeaconRole
import kotlinx.coroutines.launch
@Composable
fun MainScreen(
    viewModel: WorkSafeViewModel,
    onStartBeacon: (BeaconRole) -> Unit,
    onResolveBeaconIssue: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<WorkSafeDialogState?>(null) }
    val scope = rememberCoroutineScope()
    val page = viewModel.page
    val profile = viewModel.profile
    var serverSettingsOpen by remember(page) { mutableStateOf(false) }
    var secretTapCount by remember(page) { mutableIntStateOf(0) }
    var secretTapStartedAt by remember(page) { mutableLongStateOf(0L) }

    if (serverSettingsOpen && page == WorkSafePage.LOGIN && !viewModel.accountBusy) {
        ServerAddressDialog(
            currentAddress = viewModel.serverAddress,
            onDismiss = { serverSettingsOpen = false },
            onSave = { address ->
                viewModel.saveServerAddress(address).also { error ->
                    if (error == null) {
                        serverSettingsOpen = false
                        actionError = null
                        dialog = null
                    }
                }
            }
        )
    }

    BackHandler(enabled = viewModel.accountBusy) { }
    BackHandler(enabled = page != WorkSafePage.LOGIN && page != WorkSafePage.HOME && !viewModel.accountBusy) {
        menuExpanded = false
        viewModel.navigate(WorkSafePage.HOME)
    }

    val title = when (page) {
        WorkSafePage.LOGIN, WorkSafePage.HOME -> "WORK SAFE"
        WorkSafePage.BEACON_TRANSMITTER -> "비콘 송신 설정"
        WorkSafePage.PERSONAL_INFO -> "개인 정보 변경"
        WorkSafePage.THEME -> "화면 모드 변경"
        WorkSafePage.PASSWORD -> "비밀번호 변경"
        WorkSafePage.WITHDRAW -> "회원 탈퇴"
    }
    val subtitle = when (page) {
        WorkSafePage.LOGIN -> "작업자 안전 모니터링"
        WorkSafePage.HOME -> "${profile.name.ifBlank { "작업자" }} · ID ${profile.workerId}"
        else -> null
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                .padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 20.dp)
        ) {
            Box(Modifier.fillMaxWidth()) {
                WorkSafeHeader(
                    title = title,
                    subtitle = subtitle,
                    brand = page == WorkSafePage.LOGIN || page == WorkSafePage.HOME,
                    onMenuClick = if (page == WorkSafePage.LOGIN || viewModel.accountBusy) null else {
                        { menuExpanded = !menuExpanded }
                    },
                    onTitleTap = if (page == WorkSafePage.HOME && !viewModel.accountBusy) {
                        {
                            val now = SystemClock.elapsedRealtime()
                            if (secretTapCount == 0 || now - secretTapStartedAt >= 3_000L) {
                                secretTapCount = 0
                                secretTapStartedAt = now
                            }
                            secretTapCount++
                            if (secretTapCount == 7) {
                                secretTapCount = 0
                                menuExpanded = false
                                viewModel.navigate(WorkSafePage.BEACON_TRANSMITTER)
                            }
                        }
                    } else null
                )
                Box(Modifier.align(Alignment.BottomEnd)) {
                    SettingsDropdown(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onSelect = {
                            menuExpanded = false
                            viewModel.navigate(it)
                        }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (viewModel.isDevelopment && page != WorkSafePage.LOGIN) {
                WorkSafeCard {
                    androidx.compose.material3.Text(
                        "개발 모드 · 서버 연결 없음",
                        style = MaterialTheme.typography.titleSmall
                    )
                    androidx.compose.material3.Text(
                        "센서·BLE·워치 수신을 테스트할 수 있습니다. SOS는 연습으로만 동작합니다.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            if (page == WorkSafePage.HOME && !viewModel.isDevelopment &&
                (viewModel.profileLoading || viewModel.profileError != null)) {
                WorkSafeCard {
                    androidx.compose.material3.Text(if (viewModel.profileLoading) "작업자 정보를 불러오는 중…"
                        else viewModel.profileError.orEmpty())
                    if (!viewModel.profileLoading) WorkSafeButton(text = "작업자 정보 다시 불러오기",
                        enabled = !viewModel.accountBusy, onClick = { scope.launch { viewModel.refreshProfile() } })
                }
                Spacer(Modifier.height(12.dp))
            }
            Box(Modifier.weight(1f)) {
                when (page) {
                    WorkSafePage.LOGIN -> LoginScreen(
                        onServerSettings = { serverSettingsOpen = true },
                        onLogin = { workerId, password ->
                            if (!viewModel.accountBusy) {
                                actionError = null
                                dialog = null
                                scope.launch {
                                    actionError = viewModel.login(viewModel.serverAddress, workerId, password)
                                }
                            }
                        },
                        busy = viewModel.accountBusy,
                        errorMessage = actionError,
                        noticeMessage = if (actionError == null) viewModel.sessionNotice else null
                    )
                    WorkSafePage.HOME -> HomeScreen(
                        profile = profile,
                        motion = viewModel.motionReading,
                        watch = viewModel.watch,
                        watchNow = viewModel.watchNow,
                        onRefreshWatch = viewModel::refreshWatch,
                        fallMessage = viewModel.fallMessage,
                        fallAlert = viewModel.fallAlert,
                        beacon = viewModel.beacon,
                        onStartReceiver = { onStartBeacon(BeaconRole.RECEIVER) },
                        onStopBeacon = viewModel::stopBeacon,
                        onResolveBeaconIssue = onResolveBeaconIssue,
                        accountBusy = viewModel.accountBusy,
                        onLogout = {
                            if (!viewModel.accountBusy) {
                                actionError = null
                                dialog = null
                                scope.launch {
                                    viewModel.logout()?.let {
                                        dialog = WorkSafeDialogState(WorkSafeDialogKind.FAILURE, it)
                                    }
                                }
                            }
                        }
                    )
                    WorkSafePage.BEACON_TRANSMITTER -> BeaconTransmitterScreen(
                        savedConfig = viewModel.transmitterConfig,
                        snapshot = viewModel.beacon,
                        onSaveConfig = { viewModel.saveTransmitterConfig(it) },
                        onStartTransmitter = { config ->
                            if (viewModel.saveTransmitterConfig(config)) {
                                onStartBeacon(BeaconRole.TRANSMITTER)
                            }
                        },
                        onStopBeacon = viewModel::stopBeacon,
                        onReturnToReceiver = {
                            viewModel.navigate(WorkSafePage.HOME)
                            onStartBeacon(BeaconRole.RECEIVER)
                        },
                        onResolveIssue = onResolveBeaconIssue
                    )
                    WorkSafePage.THEME -> ThemeSettingsScreen(viewModel.darkTheme, viewModel::changeTheme)
                    WorkSafePage.PERSONAL_INFO -> PersonalInfoScreen(
                        profile = profile,
                        busy = viewModel.accountBusy,
                        isDevelopment = viewModel.isDevelopment,
                        loaded = viewModel.profileLoaded,
                        loading = viewModel.profileLoading,
                        revision = viewModel.profileRevision,
                        errorMessage = viewModel.profileError,
                        onReload = { scope.launch { viewModel.refreshProfile() } },
                        onSave = { name, gender, age ->
                            if (!viewModel.accountBusy) {
                                dialog = null
                                scope.launch {
                                    val error = viewModel.updateProfile(name, gender, age)
                                    if (error == null) {
                                        viewModel.navigate(WorkSafePage.HOME)
                                        dialog = WorkSafeDialogState(
                                            WorkSafeDialogKind.SUCCESS,
                                            if (viewModel.isDevelopment) {
                                                "개발 모드: 프로필 변경 사항을 앱에만 임시 반영했으며 서버에는 전송하지 않았습니다."
                                            } else {
                                                "개인 정보가 저장되었습니다."
                                            }
                                        )
                                    } else {
                                        dialog = WorkSafeDialogState(WorkSafeDialogKind.FAILURE, error)
                                    }
                                }
                            }
                        }
                    )
                    WorkSafePage.PASSWORD -> if (viewModel.isDevelopment) {
                        WorkSafeCard {
                            androidx.compose.material3.Text("개발 모드에서는 비밀번호 변경을 사용할 수 없습니다.")
                            androidx.compose.material3.Text(
                                "개발 계정은 서버 계정이 아니며, 비밀번호 변경 요청은 전송되지 않습니다.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        PasswordSettingsScreen(
                            busy = viewModel.accountBusy,
                            onSave = { current, new, confirmation ->
                                if (!viewModel.accountBusy) {
                                    dialog = null
                                    scope.launch {
                                        val error = viewModel.changePassword(current, new, confirmation)
                                        dialog = if (error == null) {
                                            WorkSafeDialogState(
                                                WorkSafeDialogKind.SUCCESS,
                                                "비밀번호가 변경되었습니다. 새 비밀번호로 다시 로그인하세요."
                                            )
                                        } else {
                                            WorkSafeDialogState(WorkSafeDialogKind.FAILURE, error)
                                        }
                                    }
                                }
                            }
                        )
                    }
                    WorkSafePage.WITHDRAW -> if (viewModel.isDevelopment) {
                        WorkSafeCard {
                            androidx.compose.material3.Text("개발 모드에서는 회원 탈퇴를 사용할 수 없습니다.")
                            androidx.compose.material3.Text(
                                "개발 계정은 서버에 등록되어 있지 않습니다. 홈에서 로그아웃하면 개발 모드가 종료됩니다.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        WithdrawScreen(
                            server = viewModel.serverAddress,
                            workerId = profile.workerId,
                            busy = viewModel.accountBusy,
                            onWithdraw = { password ->
                                if (!viewModel.accountBusy) {
                                    dialog = null
                                    scope.launch {
                                        val error = viewModel.withdraw(password)
                                        dialog = if (error == null) {
                                            WorkSafeDialogState(
                                                WorkSafeDialogKind.SUCCESS,
                                                "회원 탈퇴가 완료되었습니다. 이 작업자 계정으로 다시 로그인할 수 없습니다."
                                            )
                                        } else {
                                            WorkSafeDialogState(WorkSafeDialogKind.FAILURE, error)
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    dialog?.let { state ->
        WorkSafeDialog(state, onDismiss = { dialog = null }, onConfirmWithdrawal = { dialog = null })
    }
    SosCountdownDialog(viewModel)
}

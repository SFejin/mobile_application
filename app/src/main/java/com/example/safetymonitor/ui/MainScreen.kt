package com.example.safetymonitor.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.safetymonitor.detection.beacon.BeaconDetector
import com.example.safetymonitor.detection.DetectionStatus
import com.example.safetymonitor.detection.DetectorType
import com.example.safetymonitor.detection.fall.FallDetector
import com.example.safetymonitor.detection.vital.VitalDetector
import com.example.safetymonitor.manager.SafetyManager
import java.util.Locale

private data class AppPalette(
    val screenBackground: Color,
    val cardBackground: Color,
    val alertBackground: Color,
    val profileBackground: Color,
    val inputBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val successGreen: Color,
    val dangerRed: Color,
    val brightRed: Color,
    val gold: Color,
    val loginYellow: Color,
    val neutralButtonBackground: Color,
    val logoutBackground: Color,
    val logoutText: Color
)

private val LightPalette = AppPalette(
    screenBackground = Color(0xFFF6F5F2),
    cardBackground = Color.White,
    alertBackground = Color(0xFFF5D9D2),
    profileBackground = Color(0xFFE9E7E2),
    inputBackground = Color(0xFFE4E3DF),
    textPrimary = Color(0xFF181818),
    textSecondary = Color(0xFF666666),
    successGreen = Color(0xFF18894D),
    dangerRed = Color(0xFFAA4741),
    brightRed = Color(0xFFFF2020),
    gold = Color(0xFFB89832),
    loginYellow = Color(0xFFFFC400),
    neutralButtonBackground = Color(0xFFE5E4DF),
    logoutBackground = Color(0xFFFAFAFC),
    logoutText = Color(0xFFFF2020)
)

// 화면 모드 변경 토글이 사용하는 색상이다. 새 화면도 이 팔레트를 통해 색을 선택한다.
private val DarkPalette = AppPalette(
    screenBackground = Color(0xFF111111),
    cardBackground = Color(0xFF242423),
    alertBackground = Color(0xFF4B2A29),
    profileBackground = Color(0xFF242423),
    inputBackground = Color(0xFF363634),
    textPrimary = Color(0xFFF2F0EB),
    textSecondary = Color(0xFFAAA7A2),
    successGreen = Color(0xFF18A65D),
    dangerRed = Color(0xFFD88980),
    brightRed = Color(0xFFFF3434),
    gold = Color(0xFFB89832),
    loginYellow = Color(0xFFFFC400),
    neutralButtonBackground = Color(0xFF3A3A38),
    logoutBackground = Color(0xFF242423),
    logoutText = Color(0xFFF2F0EB)
)

private val LocalAppPalette = staticCompositionLocalOf { LightPalette }

@get:Composable private val ScreenBackground get() = LocalAppPalette.current.screenBackground
@get:Composable private val CardBackground get() = LocalAppPalette.current.cardBackground
@get:Composable private val AlertBackground get() = LocalAppPalette.current.alertBackground
@get:Composable private val ProfileBackground get() = LocalAppPalette.current.profileBackground
@get:Composable private val InputBackground get() = LocalAppPalette.current.inputBackground
@get:Composable private val TextPrimary get() = LocalAppPalette.current.textPrimary
@get:Composable private val TextSecondary get() = LocalAppPalette.current.textSecondary
@get:Composable private val SuccessGreen get() = LocalAppPalette.current.successGreen
@get:Composable private val DangerRed get() = LocalAppPalette.current.dangerRed
@get:Composable private val BrightRed get() = LocalAppPalette.current.brightRed
@get:Composable private val Gold get() = LocalAppPalette.current.gold
@get:Composable private val LoginYellow get() = LocalAppPalette.current.loginYellow
@get:Composable private val NeutralButtonBackground get() = LocalAppPalette.current.neutralButtonBackground
@get:Composable private val LogoutBackground get() = LocalAppPalette.current.logoutBackground
@get:Composable private val LogoutText get() = LocalAppPalette.current.logoutText

private enum class AppScreen {
    LOGIN,
    DASHBOARD,
    PROFILE,
    DISPLAY_MODE,
    PASSWORD,
    WITHDRAW
}

private enum class PendingAction {
    SAVE_PROFILE,
    CHANGE_PASSWORD,
    WITHDRAW_ACCOUNT
}

private enum class ResultType {
    SUCCESS,
    FAILURE
}

private data class WorkerProfile(
    val name: String = "홍길동",
    val gender: String = "남",
    val age: String = "29",
    // TODO: 서버 연동 후 로그인한 작업자 정보를 조회해 관리자가 부여한 직급·소속·ID로 교체한다.
    val role: String = "사원",
    val team: String = "생산1팀",
    val id: String = "1024"
)

@Suppress("UNUSED_PARAMETER")
@Composable
fun MainScreen(
    safetyManager: SafetyManager,
    fallDetector: FallDetector,
    vitalDetector: VitalDetector,
    beaconDetector: BeaconDetector
) {
    val context = LocalContext.current
    val displayPreferences = remember(context) {
        context.getSharedPreferences("display_mode", Context.MODE_PRIVATE)
    }
    var currentScreen by remember { mutableStateOf(AppScreen.LOGIN) }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var resultType by remember { mutableStateOf<ResultType?>(null) }
    var lightMode by remember { mutableStateOf(displayPreferences.getBoolean("light_mode", true)) }
    var newPassword by remember { mutableStateOf("") }
    var passwordConfirmation by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf(WorkerProfile()) }
    var pendingProfile by remember { mutableStateOf<WorkerProfile?>(null) }
    var acceleration by remember { mutableStateOf<Float?>(null) }
    var fallStage by remember { mutableStateOf("대기") }
    var fallDetected by remember { mutableStateOf(false) }
    var showFallAlert by remember { mutableStateOf(false) }

    // 화면에 실시간 수치로 표시하는 값은 가속도뿐이다. 낙상 판정에는 가속도계와 자이로스코프를 함께 사용한다.
    // BLE·워치·생체신호·서버 상태는 아래 각 카드에서 Null로 표시한다.
    // TODO: BLE·워치·심박수·SpO₂·서버 기능 구현 시 각각의 관측값을 이 화면 상태에 연결한다.
    // 메인 화면이 보이는 동안에만 센서 감지를 실행한다.
    if (currentScreen == AppScreen.DASHBOARD) {
        DisposableEffect(safetyManager, fallDetector) {
            acceleration = null
            fallStage = "대기"
            fallDetected = false
            fallDetector.setAccelerationListener { value -> acceleration = value }
            safetyManager.start { event ->
                if (event.type == DetectorType.FALL) {
                    // 어떤 단계에서 멈추는지 볼 수 있도록 감지기의 상태 메시지를 표시한다.
                    fallStage = event.message
                    if (event.status == DetectionStatus.ALERT) {
                        fallDetected = true
                        showFallAlert = true
                    } else if (fallDetector.currentState == FallDetector.State.NORMAL) {
                        fallDetected = false
                    }
                }
                // TODO: 다른 감지기의 SafetyEvent도 연결되면 해당 화면 상태를 갱신한다.
            }

            onDispose {
                fallDetector.setAccelerationListener(null)
                safetyManager.stop()
                showFallAlert = false
            }
        }
    }

    val palette = if (lightMode) LightPalette else DarkPalette
    CompositionLocalProvider(LocalAppPalette provides palette) {
    MaterialTheme(
        colorScheme = if (lightMode) {
            lightColorScheme(
                primary = palette.gold,
                background = palette.screenBackground,
                surface = palette.cardBackground,
                onBackground = palette.textPrimary,
                onSurface = palette.textPrimary
            )
        } else {
            darkColorScheme(
                primary = palette.gold,
                background = palette.screenBackground,
                surface = palette.cardBackground,
                onBackground = palette.textPrimary,
                onSurface = palette.textPrimary
            )
        }
    ) {
    BackHandler(
        enabled = currentScreen != AppScreen.LOGIN && currentScreen != AppScreen.DASHBOARD
    ) {
        currentScreen = AppScreen.DASHBOARD
    }

    when (currentScreen) {
        AppScreen.LOGIN -> LoginScreen(onLogin = { currentScreen = AppScreen.DASHBOARD })
        AppScreen.DASHBOARD -> DashboardScreen(
            acceleration = acceleration,
            fallStage = fallStage,
            fallDetected = fallDetected,
            profile = profile,
            onNavigate = { currentScreen = it },
            onLogout = { currentScreen = AppScreen.LOGIN }
        )
        AppScreen.PROFILE -> ProfileScreen(
            profile = profile,
            onNavigate = { currentScreen = it },
            onSave = {
                // TODO: 서버 연동 후 변경 가능한 개인정보를 API에 저장하고, 성공 응답으로 profile을 갱신한다.
                pendingProfile = it
                pendingAction = PendingAction.SAVE_PROFILE
            }
        )
        AppScreen.DISPLAY_MODE -> DisplayModeScreen(
            lightMode = lightMode,
            onModeChange = {
                lightMode = it
                displayPreferences.edit().putBoolean("light_mode", it).apply()
            },
            onNavigate = { currentScreen = it }
        )
        AppScreen.PASSWORD -> PasswordScreen(
            newPassword = newPassword,
            passwordConfirmation = passwordConfirmation,
            onNewPasswordChange = { newPassword = it },
            onPasswordConfirmationChange = { passwordConfirmation = it },
            onNavigate = { currentScreen = it },
            onChangePassword = { pendingAction = PendingAction.CHANGE_PASSWORD }
        )
        AppScreen.WITHDRAW -> WithdrawScreen(
            onNavigate = { currentScreen = it },
            onWithdraw = { pendingAction = PendingAction.WITHDRAW_ACCOUNT }
        )
    }

    if (showFallAlert && currentScreen == AppScreen.DASHBOARD) {
        AlertDialog(
            onDismissRequest = { showFallAlert = false },
            title = { Text("낙상 감지됨") },
            text = { Text("충격, 회전과 정지 상태가 확인되었습니다.") },
            confirmButton = {
                Button(onClick = { showFallAlert = false }) {
                    Text("확인")
                }
            }
        )
    }

    pendingAction?.let { action ->
        ConfirmDialog(
            onConfirm = {
                resultType = when (action) {
                    PendingAction.SAVE_PROFILE -> {
                        pendingProfile?.let { profile = it }
                        pendingProfile = null
                        ResultType.SUCCESS
                    }
                    PendingAction.CHANGE_PASSWORD -> {
                        if (newPassword.isNotBlank() && newPassword == passwordConfirmation) {
                            ResultType.SUCCESS
                        } else {
                            ResultType.FAILURE
                        }
                    }
                    else -> ResultType.SUCCESS
                }
                pendingAction = null
            },
            onCancel = {
                pendingProfile = null
                pendingAction = null
            }
        )
    }

    resultType?.let { result ->
        ResultDialog(
            resultType = result,
            onDismiss = {
                if (result == ResultType.SUCCESS && currentScreen == AppScreen.WITHDRAW) {
                    currentScreen = AppScreen.LOGIN
                }
                resultType = null
            }
        )
    }
    }
    }
}

@Composable
private fun LoginScreen(onLogin: () -> Unit) {
    var workerId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardContainer(CardBackground) {
            Text("WORK SAFE", color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Black)
            Text("작업자 안전 모니터링", color = TextSecondary, fontSize = 10.sp)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.width(48.dp).height(2.dp).background(LoginYellow))
        }

        CardContainer(CardBackground) {
            Text("작업자 로그인", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("관리자가 발급한 작업자 ID로 로그인", color = TextSecondary, fontSize = 10.sp)
        }

        Text("작업자 ID", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = workerId,
            onValueChange = { workerId = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("숫자 ID 입력", fontSize = 12.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Text("비밀번호", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("비밀번호 입력", fontSize = 12.sp) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )

        Button(
            onClick = onLogin,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = LoginYellow,
                contentColor = Color.Black
            )
        ) {
            Text("로그인", fontWeight = FontWeight.Bold)
        }

        CardContainer(ProfileBackground) {
            Text("초기 비밀번호 0000", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("비밀번호 초기화는 관리자에게 문의하세요.", fontSize = 10.sp)
        }
    }
}

@Composable
private fun DashboardScreen(
    acceleration: Float?,
    fallStage: String,
    fallDetected: Boolean,
    profile: WorkerProfile,
    onNavigate: (AppScreen) -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        containerColor = ScreenBackground,
        bottomBar = { LogoutButton(onLogout) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DashboardHeader(profile, onNavigate)
            ServerStatusCard()
            AlertCard(acceleration, fallDetected)
            SensorCard(acceleration, fallStage)
            WorkerCard(profile)
        }
    }
}

@Composable
private fun DashboardHeader(profile: WorkerProfile, onNavigate: (AppScreen) -> Unit) {
    MenuHeader(onNavigate = onNavigate) {
        Column(Modifier.weight(1f)) {
            Text("WORK SAFE", color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Black)
            Text("${profile.name} · ${profile.team} · ID ${profile.id}", color = TextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ProfileScreen(
    profile: WorkerProfile,
    onNavigate: (AppScreen) -> Unit,
    onSave: (WorkerProfile) -> Unit
) {
    var name by remember(profile) { mutableStateOf(profile.name) }
    var gender by remember(profile) { mutableStateOf(profile.gender) }
    var age by remember(profile) { mutableStateOf(profile.age) }

    SettingsScaffold("개인 정보 변경", onNavigate) {
        CardContainer(CardBackground) {
            Text("개인 정보 변경", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            FormLabel("이름")
            FilledInput(name, { name = it })
            FormLabel("성별")
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("남" to "남성", "여" to "여성").forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .selectable(
                                selected = gender == value,
                                onClick = { gender = value },
                                role = Role.RadioButton
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = gender == value, onClick = null)
                        Text(label, fontSize = 12.sp)
                    }
                }
            }
            FormLabel("나이")
            // 숫자 키보드만으로는 붙여넣기를 막지 못하므로 입력값에서도 숫자만 남긴다.
            FilledInput(age, { input -> age = input.filter { it in '0'..'9' }.take(3) }, KeyboardType.Number)
            Spacer(Modifier.height(8.dp))
            GoldButton("변경사항 저장", enabled = age.isNotBlank()) {
                onSave(profile.copy(name = name, gender = gender, age = age))
            }
        }
    }
}

@Composable
private fun DisplayModeScreen(
    lightMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    onNavigate: (AppScreen) -> Unit
) {
    SettingsScaffold("화면 모드 변경", onNavigate) {
        CardContainer(CardBackground) {
            Text("화면 모드 변경", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("앱 화면 테마를 선택합니다.", color = TextSecondary, fontSize = 10.sp)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Light mode", modifier = Modifier.weight(1f), fontSize = 13.sp)
                Switch(checked = lightMode, onCheckedChange = onModeChange)
            }
        }
    }
}

@Composable
private fun PasswordScreen(
    newPassword: String,
    passwordConfirmation: String,
    onNewPasswordChange: (String) -> Unit,
    onPasswordConfirmationChange: (String) -> Unit,
    onNavigate: (AppScreen) -> Unit,
    onChangePassword: () -> Unit
) {
    var currentPassword by remember { mutableStateOf("") }

    SettingsScaffold("비밀번호 변경", onNavigate) {
        CardContainer(CardBackground) {
            Text("비밀번호 변경", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            FormLabel("현재 비밀번호")
            FilledInput(currentPassword, { currentPassword = it }, password = true, hint = "••••")
            FormLabel("새 비밀번호")
            FilledInput(newPassword, onNewPasswordChange, password = true, hint = "새 비밀번호 입력")
            FormLabel("새 비밀번호 확인")
            FilledInput(
                passwordConfirmation,
                onPasswordConfirmationChange,
                password = true,
                hint = "다시 입력"
            )
            Spacer(Modifier.height(8.dp))
            GoldButton("비밀번호 변경", onClick = onChangePassword)
        }
    }
}

@Composable
private fun WithdrawScreen(
    onNavigate: (AppScreen) -> Unit,
    onWithdraw: () -> Unit
) {
    SettingsScaffold("회원 탈퇴", onNavigate) {
        CardContainer(CardBackground) {
            Text("회원 탈퇴", color = DangerRed, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "계정 사용이 중지되며 관리자 확인이 필요할 수 있습니다.",
                color = TextSecondary,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onWithdraw,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFAA4741))
            ) {
                Text("회원 탈퇴", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettingsScaffold(
    title: String,
    onNavigate: (AppScreen) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MenuHeader(onNavigate, onBack = { onNavigate(AppScreen.DASHBOARD) }) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }
        content()
    }
}

@Composable
private fun MenuHeader(
    onNavigate: (AppScreen) -> Unit,
    onBack: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val darkSettingsHeader = onBack != null && LocalAppPalette.current == DarkPalette

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (darkSettingsHeader) ScreenBackground else CardBackground)
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로가기", tint = TextPrimary)
            }
        }
        content()
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = if (darkSettingsHeader) {
                    Modifier.background(CardBackground, RoundedCornerShape(5.dp))
                } else {
                    Modifier
                }
            ) {
                Icon(Icons.Default.Menu, contentDescription = "메뉴", tint = TextPrimary)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                MenuItem("개인 정보 변경") { onNavigate(AppScreen.PROFILE) }
                MenuItem("화면 모드 변경") { onNavigate(AppScreen.DISPLAY_MODE) }
                MenuItem("비밀번호 변경") { onNavigate(AppScreen.PASSWORD) }
                MenuItem("회원 탈퇴", DangerRed) { onNavigate(AppScreen.WITHDRAW) }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, color: Color? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = color ?: TextPrimary, fontSize = 12.sp) },
        onClick = onClick
    )
}

@Composable
private fun FormLabel(text: String) {
    Text(text, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp), fontSize = 10.sp)
}

@Composable
private fun FilledInput(
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    hint: String = ""
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(hint, fontSize = 11.sp) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = InputBackground,
            unfocusedContainerColor = InputBackground,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}

@Composable
private fun GoldButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(3.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Gold)
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ConfirmDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        CardContainer(CardBackground) {
            Text("작업을 실행할까요?", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("이 작업은 되돌릴 수 없습니다.", color = TextSecondary, fontSize = 10.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton("취소", NeutralButtonBackground, TextPrimary, onCancel, Modifier.weight(1f))
                DialogButton("확인", Gold, Color.White, onConfirm, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ResultDialog(resultType: ResultType, onDismiss: () -> Unit) {
    val success = resultType == ResultType.SUCCESS
    Dialog(onDismissRequest = onDismiss) {
        CardContainer(CardBackground) {
            Text(
                text = if (success) "✓  작업 완료" else "!  작업 실패",
                color = if (success) SuccessGreen else DangerRed,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (success) {
                    "요청한 작업이 정상적으로 처리되었습니다."
                } else {
                    "작업을 처리하지 못했습니다. 잠시 후 다시 시도해주세요."
                },
                fontSize = 10.sp
            )
            Spacer(Modifier.height(12.dp))
            DialogButton(
                text = if (success) "확인" else "닫기",
                background = if (success) Gold else NeutralButtonBackground,
                contentColor = if (success) Color.White else TextPrimary,
                onClick = onDismiss
            )
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    background: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(3.dp),
        colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ServerStatusCard() {
    CardContainer(CardBackground) {
        // TODO: 서버 연결 상태 확인 기능 구현 시 실제 연결 결과로 교체한다.
        InfoRow("서버 연결 상태", "Null")
    }
}

@Composable
private fun AlertCard(acceleration: Float?, fallDetected: Boolean) {
    // 낙상 경고는 FallDetector의 전체 상태 머신이 ALERT를 보냈을 때만 표시한다.
    // 단순히 가속도값이 커지는 것만으로는 위험 상태로 바꾸지 않는다.
    val title = when {
        fallDetected -> "낙상 감지됨"
        acceleration == null -> "측정 대기"
        else -> "정상 관측 중"
    }
    CardContainer(if (fallDetected) AlertBackground else CardBackground) {
        Text(
            "낙상 감지",
            color = if (fallDetected) DangerRed else TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                fallDetected -> "충격·회전·정지 조건이 확인되었습니다."
                acceleration == null -> "가속도 센서값을 기다리는 중입니다."
                else -> "가속도 ${formatAcceleration(acceleration)} · 낙상 징후 없음"
            },
            color = TextSecondary,
            fontSize = 10.sp
        )
        if (fallDetected) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.background(Color(0xFFAA4741), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("HIGH", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SensorCard(acceleration: Float?, fallStage: String) {
    CardContainer(CardBackground) {
        Text("센서 정보", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        InfoRow("가속도 센서", acceleration?.let(::formatAcceleration) ?: "Null", BrightRed)
        Text("낙상 판정 단계: $fallStage", color = TextSecondary, fontSize = 10.sp)
        // TODO: BLE 스캔 결과를 연결한다.
        InfoRow("BLE 접근 감지", "Null")
        // TODO: 워치 연결 상태 확인 기능 구현 시 실제 연결 결과로 교체한다.
        InfoRow("Galaxy Watch", "Null")
        // TODO: 워치 생체 데이터 수신 기능 구현 시 실제 심박수로 교체한다.
        InfoRow("심박수", "Null")
        // TODO: 워치 생체 데이터 수신 기능 구현 시 실제 산소포화도로 교체한다.
        InfoRow("SpO₂", "Null")
    }
}

@Composable
private fun WorkerCard(profile: WorkerProfile) {
    CardContainer(ProfileBackground) {
        Text("개인 / 작업자 정보", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("이름  ${profile.name}    성별  ${profile.gender}    나이  ${profile.age}", fontSize = 10.sp)
        Spacer(Modifier.height(9.dp))
        Text("직급  ${profile.role}    소속  ${profile.team}    ID  ${profile.id}", fontSize = 10.sp)
    }
}

private fun formatAcceleration(value: Float): String =
    String.format(Locale.KOREA, "%.1f m/s²", value)

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 12.sp)
        Text(value, color = valueColor ?: TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CardContainer(
    backgroundColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(5.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor, contentColor = TextPrimary),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            content = content
        )
    }
}

@Composable
private fun LogoutButton(onLogout: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(ScreenBackground).padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LogoutBackground, contentColor = LogoutText),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
        ) {
            Text("로그아웃", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

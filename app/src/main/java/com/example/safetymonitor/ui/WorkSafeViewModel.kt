package com.example.safetymonitor.ui

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.safetymonitor.BuildConfig
import com.example.safetymonitor.detection.beacon.BeaconConfig
import com.example.safetymonitor.detection.beacon.BeaconIssue
import com.example.safetymonitor.detection.beacon.BeaconMonitoringService
import com.example.safetymonitor.detection.beacon.BeaconMonitoringStore
import com.example.safetymonitor.detection.beacon.BeaconPermissions
import com.example.safetymonitor.detection.beacon.BeaconProtocol
import com.example.safetymonitor.detection.beacon.BeaconRole
import com.example.safetymonitor.detection.beacon.BeaconSettings
import com.example.safetymonitor.detection.beacon.BeaconStatus
import com.example.safetymonitor.detection.fall.FallMonitoringService
import com.example.safetymonitor.detection.fall.FallMonitoringStore
import com.example.safetymonitor.detection.fall.MotionReading
import com.example.safetymonitor.network.WorkerApiClient
import com.example.safetymonitor.network.WorkerApiException
import com.example.safetymonitor.network.WorkerPasswordChange
import com.example.safetymonitor.network.WorkerPasswordConfirmation
import com.example.safetymonitor.network.WorkerProfileUpdate
import com.example.safetymonitor.sos.SosController
import com.example.safetymonitor.sos.SosPhase
import com.example.safetymonitor.sos.SosServer
import com.example.safetymonitor.sos.SosSession
import com.example.safetymonitor.sos.SosStore
import com.example.safetymonitor.sos.loginWorkerSession
import com.example.safetymonitor.watch.WatchDataRepository
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The worker login and SOS share one in-memory server session. */
class WorkSafeViewModel(application: Application) : AndroidViewModel(application) {
    var watch by mutableStateOf(WatchDataRepository.state.value)
        private set
    var watchNow by mutableStateOf(System.currentTimeMillis())
        private set
    private val preferences = application.getSharedPreferences("worksafe_theme", 0)
    var sos by mutableStateOf(SosStore.state.value)
        private set
    var accountBusy by mutableStateOf(false)
        private set
    val serverAddress: String
        get() = preferences.getString("sos_server", null) ?: SosServer.DEFAULT_SERVER
    var sessionNotice by mutableStateOf<String?>(null)
        private set
    var sosSession by mutableStateOf(SosStore.session.value)
        private set
    val isDevelopment: Boolean
        get() = sosSession?.isDevelopment == true
    val sosTestAvailable = BuildConfig.DEBUG
    val sosNotificationsEnabled: Boolean
        get() = getApplication<Application>().getSystemService(NotificationManager::class.java)
            .areNotificationsEnabled()
    var profile by mutableStateOf(WorkerProfile(workerId = sosSession?.workerId?.toString().orEmpty()))
        private set
    var page by mutableStateOf(if (sosSession != null) WorkSafePage.HOME else WorkSafePage.LOGIN)
        private set
    var motionReading by mutableStateOf(MotionReading())
        private set
    private var monitoringMessage by mutableStateOf("센서 측정 대기 중")
    private var monitoringUnavailable by mutableStateOf(false)
    private var monitoringStart: Job? = null
    val fallMessage: String
        get() = if (isDevelopment && monitoringUnavailable) {
            "개발 모드 · 센서 측정을 시작할 수 없습니다."
        } else monitoringMessage
    var fallAlert by mutableStateOf(false)
        private set
    var beacon by mutableStateOf(BeaconMonitoringStore.state.value)
        private set
    var transmitterConfig by mutableStateOf(BeaconSettings.load(application))
        private set
    var pendingBeaconRole: BeaconRole? = null
        private set
    private var resumeBeaconAfterSettings = false

    init {
        SosStore.initialize(application)
        sos = SosStore.state.value
        if (sosSession == null && !sos.active) {
            FallMonitoringService.stop(application)
            BeaconMonitoringService.stop(application)
        }
        reconcileSession()
        viewModelScope.launch { WatchDataRepository.state.collect { watch = it } }
        viewModelScope.launch {
            while (true) {
                val target = WatchDataRepository.capture()
                withContext(Dispatchers.IO) { WatchDataRepository.refreshBlocking(application, target) }
                delay(15_000)
            }
        }
        viewModelScope.launch {
            SosStore.state.collect {
                sos = it
                reconcileSession()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                watchNow = System.currentTimeMillis()
                reconcileSession()
            }
        }
        viewModelScope.launch {
            FallMonitoringStore.state.collect {
                motionReading = it.motion
                monitoringMessage = it.message
                fallAlert = it.alert
            }
        }
        viewModelScope.launch {
            BeaconMonitoringStore.state.collect { beacon = it }
        }
    }

    fun onAppVisible() {
        val session = authenticatedSession() ?: return
        if (accountBusy) return
        refreshWatch()
        if (!FallMonitoringStore.state.value.motion.monitoring && monitoringStart?.isActive != true) {
            monitoringStart = viewModelScope.launch {
                val started = FallMonitoringService.start(getApplication())
                if (!isCurrent(session)) return@launch
                monitoringUnavailable = !started
                if (!started && !session.isDevelopment) {
                    invalidateAccount(session, MONITORING_FAILED)
                    revokeQuietly(session)
                }
            }
        }
        if (resumeBeaconAfterSettings) {
            completeBeaconStart()
        } else if (pendingBeaconRole == null) {
            val current = BeaconMonitoringStore.state.value
            if (current.status == BeaconStatus.IDLE ||
                (current.status == BeaconStatus.BLOCKED &&
                    current.role == BeaconRole.RECEIVER &&
                    BeaconPermissions.issue(getApplication(), BeaconRole.RECEIVER) == BeaconIssue.NONE)
            ) {
                BeaconMonitoringService.startReceiver(getApplication())
            }
        }
    }

    var darkTheme by mutableStateOf(preferences.getBoolean("dark_theme", false))
        private set

    private fun api(session: SosSession) =
        WorkerApiClient(session.server, allowInsecureHttp = session.server.startsWith("http://"))

    private suspend fun accountAction(
        unknownOutcome: String,
        httpError: (WorkerApiException) -> String,
        action: suspend () -> String?
    ): String? = withContext(Dispatchers.Main.immediate) {
        if (accountBusy) return@withContext "다른 계정 요청을 처리 중입니다. 완료될 때까지 기다려주세요."
        accountBusy = true
        try {
            action()
        } catch (e: WorkerApiException) {
            httpError(e)
        } catch (_: IOException) {
            unknownOutcome
        } finally {
            accountBusy = false
        }
    }

    suspend fun login(server: String, workerId: String, candidatePassword: String): String? = accountAction(
        "서버에 연결하거나 인증 응답을 확인하지 못했습니다. 주소와 네트워크를 확인해주세요.",
        { e ->
            when (e.httpStatus) {
                401, 403 -> "작업자 ID 또는 비밀번호를 확인해주세요. 계정이 비활성화된 경우 관리자에게 문의해주세요."
                429 -> "로그인 요청이 너무 많습니다. 잠시 후 다시 시도해주세요."
                in 500..599 -> "서버 오류로 로그인하지 못했습니다. 잠시 후 다시 시도해주세요."
                else -> "로그인이 거절되었습니다 (HTTP ${e.httpStatus}). 서버 상태를 확인해주세요."
            }
        }
    ) {
        sessionNotice = null
        if (SosStore.state.value.active) return@accountAction "SOS 처리 중에는 계정을 변경할 수 없습니다."
        reconcileSession()
        if (SosStore.session.value != null) return@accountAction "이미 로그인되어 있습니다. 먼저 로그아웃해주세요."
        val id = workerId.trim().toLongOrNull()
        if (id == null || id <= 0 || candidatePassword.isBlank()) {
            return@accountAction "올바른 작업자 ID와 비밀번호를 입력해주세요."
        }
        val caller = currentCoroutineContext()
        // A returned token must be adopted by this live screen or revoked, even after cancellation.
        withContext(NonCancellable) {
            val session = try {
                loginWorkerSession(server, id, candidatePassword)
            } catch (e: IllegalArgumentException) {
                return@withContext e.message ?: "서버 인증 응답을 확인해주세요."
            } catch (_: SocketTimeoutException) {
                return@withContext "서버 응답 시간이 초과되었습니다. 잠시 후 다시 시도해주세요."
            }
            var adopted = false
            try {
                if (!caller.isActive || !viewModelScope.isActive ||
                    SosStore.session.value != null || SosStore.state.value.active
                ) return@withContext "로그인 도중 상태가 바뀌었습니다. 다시 로그인해주세요."
                sosSession = session
                SosStore.connect(session)
                val started = FallMonitoringService.start(getApplication())
                if (!caller.isActive || !viewModelScope.isActive || !isCurrent(session)) {
                    return@withContext "로그인 도중 상태가 바뀌었습니다. 다시 로그인해주세요."
                }
                if (!started && !session.isDevelopment) return@withContext MONITORING_FAILED
                if (!session.valid()) return@withContext EXPIRED
                monitoringUnavailable = !started
                profile = WorkerProfile(workerId = id.toString())
                if (!session.isDevelopment) preferences.edit().putString("sos_server", session.server).apply()
                sessionNotice = null
                page = WorkSafePage.HOME
                adopted = true
                refreshWatch()
                BeaconMonitoringService.startReceiver(getApplication())
                null
            } finally {
                if (!adopted) {
                    invalidateAccount(session)
                    revokeQuietly(session)
                }
            }
        }
    }

    fun refreshWatch() {
        val target = WatchDataRepository.capture()
        viewModelScope.launch(Dispatchers.IO) { WatchDataRepository.refreshBlocking(getApplication<Application>(), target) }
    }

    fun navigate(destination: WorkSafePage) {
        if (authenticatedSession() != null && !accountBusy && destination != WorkSafePage.LOGIN) {
            page = destination
        }
    }

    fun saveTransmitterConfig(config: BeaconConfig): Boolean {
        if (authenticatedSession() == null || !BeaconProtocol.isValid(config)) return false
        BeaconSettings.save(getApplication(), config)
        transmitterConfig = config
        return true
    }

    /** Stop the old role before a permission/settings round trip can leave the app. */
    fun prepareBeaconStart(role: BeaconRole): Boolean {
        if (authenticatedSession() == null || pendingBeaconRole != null) return false
        pendingBeaconRole = role
        BeaconMonitoringService.stop(getApplication())
        return true
    }

    fun waitForBeaconSettings() {
        if (pendingBeaconRole != null) resumeBeaconAfterSettings = true
    }

    fun completeBeaconStart() {
        val role = pendingBeaconRole ?: return
        pendingBeaconRole = null
        resumeBeaconAfterSettings = false
        if (authenticatedSession() == null) return
        when (role) {
            BeaconRole.RECEIVER -> BeaconMonitoringService.startReceiver(getApplication())
            BeaconRole.TRANSMITTER ->
                BeaconMonitoringService.startTransmitter(getApplication(), transmitterConfig)
        }
    }

    fun stopBeacon() {
        pendingBeaconRole = null
        resumeBeaconAfterSettings = false
        BeaconMonitoringService.stop(getApplication())
    }

    fun changeTheme(enabled: Boolean) {
        darkTheme = enabled
        preferences.edit().putBoolean("dark_theme", enabled).apply()
    }

    suspend fun logout(): String? = accountAction(
        "앱에서는 로그아웃했습니다. 서버의 인증 해제 여부는 확인하지 못했습니다. 네트워크와 서버 상태를 확인해주세요.",
        { e -> "앱에서는 로그아웃했습니다. 서버의 인증 해제를 확인하지 못했습니다 (HTTP ${e.httpStatus})." }
    ) {
        if (SosStore.state.value.active) return@accountAction "SOS 처리 중에는 로그아웃할 수 없습니다."
        val session = authenticatedSession() ?: return@accountAction "로그인된 계정이 없습니다."
        withContext(NonCancellable) {
            invalidateAccount(session)
            if (!session.isDevelopment) api(session).logout(session.token)
            null
        }
    }

    suspend fun updateProfile(name: String, gender: String, age: String): String? = accountAction(
        "프로필 변경 결과를 확인하지 못했습니다. 네트워크를 확인하고 입력 내용을 다시 확인해주세요.",
        { e -> "프로필 변경 결과를 확인하지 못했습니다 (HTTP ${e.httpStatus}). 입력 내용이나 서버 상태를 확인해주세요." }
    ) {
        val session = authenticatedSession() ?: return@accountAction EXPIRED
        val workerName = name.trim().ifEmpty { null }
        if (workerName != null && workerName.length > 50) return@accountAction "이름은 50자 이하로 입력해주세요."
        val sex = when (gender.trim()) {
            "" -> null
            "M", "남", "남성" -> "M"
            "F", "여", "여성" -> "F"
            "U", "기타", "미지정" -> "U"
            else -> return@accountAction "성별은 남성(M), 여성(F), 미지정(U) 중에서 입력해주세요."
        }
        val ageText = age.trim()
        val parsedAge = ageText.toIntOrNull()
        if (ageText.isNotEmpty() && (parsedAge == null || parsedAge !in 0..150)) {
            return@accountAction "나이는 0~150 사이의 숫자로 입력해주세요."
        }
        if (workerName == null && sex == null && parsedAge == null) return@accountAction "변경할 정보를 하나 이상 입력해주세요."
        if (session.isDevelopment) {
            profile = profile.copy(name = workerName ?: profile.name, gender = sex?.genderLabel() ?: profile.gender,
                age = parsedAge ?: profile.age)
            return@accountAction null
        }
        val updated = try {
            api(session).updateProfile(session.token, WorkerProfileUpdate(workerName, sex, parsedAge))
        } catch (e: WorkerApiException) {
            if (e.httpStatus != 401) throw e
            invalidateAccount(session, EXPIRED)
            return@accountAction EXPIRED
        }
        if (!isCurrent(session)) return@accountAction "계정이 변경되어 프로필 결과를 적용하지 않았습니다."
        if (authenticatedSession() == null) return@accountAction EXPIRED
        if (updated.workerId != session.workerId || !updated.enabled) {
            val message = "서버의 계정 정보가 일치하지 않거나 비활성화되었습니다. 관리자에게 확인 후 다시 로그인해주세요."
            invalidateAccount(session, message)
            return@accountAction message
        }
        profile = WorkerProfile(updated.workerId.toString(), updated.workerName, updated.sex.genderLabel(), updated.age)
        null
    }

    suspend fun changePassword(current: String, new: String, confirmation: String): String? = accountAction(
        "비밀번호 변경 여부를 확인할 수 없습니다. 자동 재시도하지 않습니다. 다시 로그인하여 확인하거나 관리자에게 문의해주세요.",
        { e -> passwordFailure("비밀번호 변경", e) }
    ) {
        val session = authenticatedSession() ?: return@accountAction EXPIRED
        if (session.isDevelopment) return@accountAction "개발 모드에서는 비밀번호를 변경할 수 없습니다."
        if (SosStore.state.value.active) return@accountAction "SOS 처리 중에는 비밀번호를 변경할 수 없습니다."
        if (current.isBlank()) return@accountAction "현재 비밀번호를 입력해주세요."
        if (new.isBlank() || new.length !in 12..64 || new.toByteArray(Charsets.UTF_8).size > 72) {
            return@accountAction "새 비밀번호는 12~64자, UTF-8 기준 72바이트 이하로 입력해주세요."
        }
        if (new != confirmation) return@accountAction "새 비밀번호와 확인 값이 일치하지 않습니다."
        withContext(NonCancellable) {
            api(session).changePassword(session.token, WorkerPasswordChange(current, new))
            val currentAccount = isCurrent(session)
            invalidateAccount(session, "비밀번호가 변경되었습니다. 새 비밀번호로 로그인해주세요.")
            if (currentAccount) null else "계정 상태가 바뀌었습니다. 변경한 비밀번호로 다시 로그인하여 확인해주세요."
        }
    }

    suspend fun withdraw(currentPassword: String): String? = accountAction(
        "회원 탈퇴 처리 여부를 확인할 수 없습니다. 자동 재시도하지 않습니다. 다시 로그인하여 확인하거나 관리자에게 문의해주세요.",
        { e -> passwordFailure("회원 탈퇴", e) }
    ) {
        val session = authenticatedSession() ?: return@accountAction EXPIRED
        if (session.isDevelopment) return@accountAction "개발 모드에서는 탈퇴할 수 없습니다. 로그아웃으로 개발 모드를 종료하세요."
        if (SosStore.state.value.active) return@accountAction "SOS 처리 중에는 탈퇴할 수 없습니다."
        if (currentPassword.isBlank()) return@accountAction "현재 비밀번호를 입력해주세요."
        withContext(NonCancellable) {
            api(session).deleteAccount(session.token, WorkerPasswordConfirmation(currentPassword))
            val currentAccount = isCurrent(session)
            invalidateAccount(session, "회원 탈퇴가 완료되었습니다.")
            if (currentAccount) null else "계정 상태가 바뀌었습니다. 탈퇴 처리 여부는 관리자에게 확인해주세요."
        }
    }

    private fun passwordFailure(action: String, error: WorkerApiException): String = when {
        // A wrong current password and a rejected bearer token can both return 401.
        error.httpStatus == 401 -> "$action 인증이 거절되었습니다. 현재 비밀번호를 확인하거나 다시 로그인해주세요."
        error.httpStatus in 400..499 -> "$action 요청이 거절되었습니다 (HTTP ${error.httpStatus}). 입력값과 계정 권한을 확인해주세요."
        else -> "$action 처리 여부를 확인할 수 없습니다 (HTTP ${error.httpStatus}). 자동 재시도하지 말고 관리자에게 확인해주세요."
    }

    private fun String?.genderLabel(): String = when (this) {
        "M" -> "남"
        "F" -> "여"
        "U" -> "미지정"
        else -> ""
    }

    private fun isCurrent(session: SosSession): Boolean = sosSession === session && SosStore.session.value === session

    private fun authenticatedSession(): SosSession? {
        reconcileSession()
        return sosSession?.takeIf { isCurrent(it) && it.valid() }
    }

    private fun reconcileSession() {
        val session = sosSession ?: return
        if ((session.valid() && isCurrent(session)) || SosStore.state.value.active) return
        sessionNotice = sessionNotice ?: EXPIRED
        clearLocalAccount(session)
    }

    private fun invalidateAccount(session: SosSession, notice: String? = null) {
        session.invalidate()
        if (!isCurrent(session)) return
        sessionNotice = notice
        if (!SosStore.state.value.active) clearLocalAccount(session)
    }

    private fun clearLocalAccount(session: SosSession) {
        if (sosSession !== session) return
        if (SosStore.session.value === session) {
            SosStore.connect(null)
            stopBeacon()
            FallMonitoringService.stop(getApplication())
        }
        sosSession = null
        profile = WorkerProfile()
        monitoringUnavailable = false
        page = WorkSafePage.LOGIN
    }

    private suspend fun revokeQuietly(session: SosSession) {
        if (!session.isDevelopment) withContext(NonCancellable) { runCatching { api(session).logout(session.token) } }
    }

    fun cancelSos(incidentId: String) {
        val state = SosStore.state.value
        if (sosSession?.let(::isCurrent) != true || state.phase != SosPhase.COUNTDOWN || state.incidentId != incidentId) return
        getApplication<Application>().startService(Intent(getApplication(), FallMonitoringService::class.java)
            .setAction(SosController.ACTION_CANCEL).putExtra(SosController.EXTRA_ID, incidentId))
    }

    fun testSos() {
        if (authenticatedSession() == null || !sosTestAvailable || SosStore.state.value.active ||
            !FallMonitoringStore.state.value.motion.monitoring
        ) return
        getApplication<Application>().startService(Intent(getApplication(), FallMonitoringService::class.java)
            .setAction(SosController.ACTION_TEST))
    }

    private companion object {
        const val EXPIRED = "인증이 만료되었거나 해제되었습니다. 다시 로그인해주세요."
        const val MONITORING_FAILED = "센서 측정을 시작할 수 없습니다. 다시 로그인해주세요."
    }

}

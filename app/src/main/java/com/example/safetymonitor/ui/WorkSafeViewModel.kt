package com.example.safetymonitor.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.safetymonitor.detection.fall.FallMonitoringService
import com.example.safetymonitor.detection.fall.FallMonitoringStore
import com.example.safetymonitor.detection.fall.MotionReading
import kotlinx.coroutines.launch

/** Offline demo account only. Credentials and profile never leave this process. */
class WorkSafeViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("worksafe_theme", 0)
    var motionReading by mutableStateOf(MotionReading())
        private set
    var fallMessage by mutableStateOf("센서 측정 대기 중")
        private set
    var fallAlert by mutableStateOf(false)
        private set

    init {
        loggedIn = FallMonitoringService.isEnabled(application)
        viewModelScope.launch {
            FallMonitoringStore.state.collect {
                motionReading = it.motion
                fallMessage = it.message
                fallAlert = it.alert
            }
        }
    }

    fun onAppVisible() {
        if (loggedIn && !FallMonitoringStore.state.value.motion.monitoring) {
            if (!FallMonitoringService.start(getApplication())) {
                loggedIn = false
                page = WorkSafePage.LOGIN
            }
        }
    }
    // Account changes survive Activity recreation, but never process death.
    private companion object {
        var demoProfile by mutableStateOf(WorkerProfile())
        var password = "0000"
        var accountActive = true
        var loggedIn = false
    }

    val profile: WorkerProfile
        get() = demoProfile
    var page by mutableStateOf(if (loggedIn) WorkSafePage.HOME else WorkSafePage.LOGIN)
        private set
    var darkTheme by mutableStateOf(preferences.getBoolean("dark_theme", false))
        private set

    fun login(workerId: String, candidatePassword: String): String? {
        if (!accountActive) return "비활성화된 데모 계정입니다. 앱 프로세스를 재시작하면 초기화됩니다."
        if (workerId.trim() != profile.workerId || candidatePassword != password) {
            return "작업자 ID 또는 비밀번호가 올바르지 않습니다."
        }
        if (!FallMonitoringService.start(getApplication())) {
            return "센서 측정을 시작할 수 없습니다. 다시 시도해주세요."
        }
        loggedIn = true
        page = WorkSafePage.HOME
        return null
    }

    fun navigate(destination: WorkSafePage) {
        if (loggedIn && destination != WorkSafePage.LOGIN) page = destination
    }

    fun updateProfile(name: String, gender: String, age: String): String? {
        if (!loggedIn) return "먼저 로그인해주세요."
        if (name.isBlank()) return "이름을 입력해주세요."
        if (gender.isBlank()) return "성별을 입력해주세요."
        val parsedAge = age.trim().toIntOrNull()
        if (parsedAge == null || parsedAge !in 1..120) return "나이는 1~120 사이의 숫자로 입력해주세요."
        demoProfile = profile.copy(name = name.trim(), gender = gender.trim(), age = parsedAge)
        return null
    }

    fun changePassword(current: String, new: String, confirmation: String): String? {
        if (!loggedIn) return "먼저 로그인해주세요."
        if (current != password) return "현재 비밀번호가 올바르지 않습니다."
        if (new.isBlank() || new.length < 4) return "새 비밀번호를 4자 이상 입력해주세요."
        if (new != confirmation) return "새 비밀번호와 확인 값이 일치하지 않습니다."
        password = new
        return null
    }

    fun changeTheme(enabled: Boolean) {
        darkTheme = enabled
        preferences.edit().putBoolean("dark_theme", enabled).apply()
    }

    fun logout() {
        FallMonitoringService.stop(getApplication())
        loggedIn = false
        page = WorkSafePage.LOGIN
    }

    fun withdraw() {
        if (!loggedIn) return
        accountActive = false
        password = ""
        logout()
    }
}

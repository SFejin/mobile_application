package com.example.safetymonitor

import android.graphics.Color
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import com.example.safetymonitor.ui.MainScreen
import com.example.safetymonitor.ui.WorkSafeTheme
import com.example.safetymonitor.ui.WorkSafeViewModel
import com.example.safetymonitor.ui.WorkSafePage

class MainActivity : ComponentActivity() {
    private val model: WorkSafeViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Monitoring also works when notification permission is declined. */ }

    override fun onResume() {
        super.onResume()
        model.onAppVisible()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LaunchedEffect(model.page) {
                if (model.page != WorkSafePage.LOGIN && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    val preferences = getSharedPreferences("notification_permission", MODE_PRIVATE)
                    if (!preferences.getBoolean("requested", false)) {
                        preferences.edit().putBoolean("requested", true).apply()
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            val darkTheme = model.darkTheme
            SideEffect {
                val barStyle = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
            }
            WorkSafeTheme(darkTheme) {
                MainScreen(model)
            }
        }
    }
}

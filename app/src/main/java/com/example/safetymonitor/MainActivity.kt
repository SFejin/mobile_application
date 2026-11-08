package com.example.safetymonitor

import android.graphics.Color
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import com.example.safetymonitor.detection.beacon.BeaconIssue
import com.example.safetymonitor.detection.beacon.BeaconPermissions
import com.example.safetymonitor.detection.beacon.BeaconRole
import com.example.safetymonitor.ui.MainScreen
import com.example.safetymonitor.ui.WorkSafeTheme
import com.example.safetymonitor.ui.WorkSafeViewModel
import com.example.safetymonitor.ui.WorkSafePage

class MainActivity : ComponentActivity() {
    private val model: WorkSafeViewModel by viewModels()
    private var notificationRequestInFlight = false
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationRequestInFlight = false
        continueBeaconStart()
    }
    private val beaconPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        model.completeBeaconStart()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        model.onAppVisible()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LaunchedEffect(model.page) {
                requestNotificationPermissionIfNeeded()
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
                MainScreen(
                    model,
                    onStartBeacon = ::requestBeaconStart,
                    onResolveBeaconIssue = ::resolveBeaconIssue
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (model.page == WorkSafePage.LOGIN || Build.VERSION.SDK_INT < 33 ||
            notificationRequestInFlight || model.pendingBeaconRole != null ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) return
        val preferences = getSharedPreferences("notification_permission", MODE_PRIVATE)
        if (!preferences.getBoolean("requested", false)) {
            preferences.edit().putBoolean("requested", true).apply()
            notificationRequestInFlight = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestBeaconStart(role: BeaconRole) {
        if (model.prepareBeaconStart(role) && !notificationRequestInFlight) {
            continueBeaconStart()
        }
    }

    private fun continueBeaconStart() {
        val role = model.pendingBeaconRole ?: return
        val permissions = BeaconPermissions.requiredPermissions(role)
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            model.completeBeaconStart()
            return
        }
        val preferences = getSharedPreferences("ble_permission_requests", MODE_PRIVATE)
        val needsSettings = missing.any {
            preferences.getBoolean(it, false) && !shouldShowRequestPermissionRationale(it)
        }
        if (needsSettings) {
            model.waitForBeaconSettings()
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        } else {
            val editor = preferences.edit()
            permissions.forEach { editor.putBoolean(it, true) }
            editor.apply()
            // Keep coarse/fine in the same request even when coarse was granted earlier.
            beaconPermissions.launch(permissions)
        }
    }

    private fun resolveBeaconIssue() {
        val role = model.beacon.role
        val action = when (BeaconPermissions.issue(this, role)) {
            BeaconIssue.BLUETOOTH_OFF -> Settings.ACTION_BLUETOOTH_SETTINGS
            BeaconIssue.LOCATION_OFF -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> {
                requestBeaconStart(role)
                return
            }
        }
        if (model.prepareBeaconStart(role)) {
            model.waitForBeaconSettings()
            startActivity(Intent(action))
        }
    }
}

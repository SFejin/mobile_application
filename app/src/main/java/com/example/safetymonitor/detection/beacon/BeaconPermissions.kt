package com.example.safetymonitor.detection.beacon

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings

/**
 * Role-specific runtime prerequisites. Nothing here prompts the user; the Activity requests
 * [requiredPermissions] and shows settings actions based on [issue].
 *
 * - Android 12+: receiver needs BLUETOOTH_SCAN + location; transmitter needs BLUETOOTH_ADVERTISE only.
 * - Android 11 and below: legacy BLUETOOTH/BLUETOOTH_ADMIN are normal (manifest-only) permissions;
 *   the receiver needs location, the transmitter needs no runtime permission.
 * BLUETOOTH_CONNECT is deliberately not required. Verified against the API 35 BluetoothAdapter docs:
 * isEnabled()/getState() only require the legacy BLUETOOTH permission for apps targeting API 30 or lower,
 * and getBluetoothLeScanner()/getBluetoothLeAdvertiser() carry no permission requirement (scan/advertise
 * calls themselves need SCAN/ADVERTISE). A SCAN/ADVERTISE grant satisfies the API 34 connectedDevice
 * FGS prerequisite. The SecurityException catches below are defensive only; they are not an expected path.
 */
object BeaconPermissions {
    /** Permissions to request together in one Activity request for [role]. */
    fun requiredPermissions(role: BeaconRole): Array<String> = when (role) {
        BeaconRole.RECEIVER ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        BeaconRole.TRANSMITTER ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                emptyArray()
            }
    }

    /** Permissions that must actually be granted. Approximate-only location is not enough to scan. */
    private fun mustBeGranted(role: BeaconRole): List<String> = when (role) {
        BeaconRole.RECEIVER ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        BeaconRole.TRANSMITTER ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                emptyList()
            }
    }

    /** First unmet prerequisite for [role], or [BeaconIssue.NONE]. Order: hardware, permission, radio, location. */
    fun issue(context: Context, role: BeaconRole): BeaconIssue {
        val app = context.applicationContext
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return BeaconIssue.UNSUPPORTED
        val adapter = adapter(app) ?: return BeaconIssue.UNSUPPORTED

        val missingPermission = mustBeGranted(role).any {
            app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermission) return BeaconIssue.PERMISSIONS

        val enabled = try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            return BeaconIssue.PERMISSIONS
        }
        if (!enabled) return BeaconIssue.BLUETOOTH_OFF

        if (role == BeaconRole.RECEIVER && !isLocationEnabled(app)) return BeaconIssue.LOCATION_OFF

        // With Bluetooth on, a missing LE scanner / advertiser means the chipset cannot do it.
        val roleSupported = try {
            if (role == BeaconRole.RECEIVER) adapter.bluetoothLeScanner != null else adapter.bluetoothLeAdvertiser != null
        } catch (_: SecurityException) {
            return BeaconIssue.PERMISSIONS
        }
        return if (roleSupported) BeaconIssue.NONE else BeaconIssue.UNSUPPORTED
    }

    internal fun adapter(context: Context): BluetoothAdapter? =
        context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

    internal fun isLocationEnabled(context: Context): Boolean {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            app.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        } else {
            @Suppress("DEPRECATION")
            try {
                Settings.Secure.getInt(app.contentResolver, Settings.Secure.LOCATION_MODE) !=
                    Settings.Secure.LOCATION_MODE_OFF
            } catch (_: Settings.SettingNotFoundException) {
                false
            }
        }
    }
}

package com.example.safetymonitor.detection.beacon

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid

/**
 * Real BLE transmitter: legacy, non-connectable but scannable advertising.
 *
 * - primary packet:   ServiceData(SERVICE_UUID, 8-byte payload) only = 26 bytes (+3 Flags = 29 <= 31)
 * - scan response:    the same 128-bit Service UUID list only      = 18 bytes
 * No device name, no TX power AD, no GATT. The two fields are never merged into one >31-byte packet.
 * One instance = one advertising session; [start] reports ACTIVE only through the success callback.
 */
class BeaconAdvertiser(context: Context) {
    private val appContext = context.applicationContext
    private val serviceUuid = ParcelUuid.fromString(BeaconProtocol.SERVICE_UUID)

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null

    val isAdvertising: Boolean get() = callback != null

    /**
     * Returns null when the framework accepted the request. [onStarted] / [onFailed] report the
     * asynchronous outcome exactly once and only while this session is still the current one.
     */
    internal fun start(
        config: BeaconConfig,
        onStarted: () -> Unit,
        onFailed: (BeaconFailure) -> Unit
    ): BeaconFailure? {
        if (callback != null) return null
        if (!BeaconProtocol.isValid(config)) {
            return BeaconFailure(BeaconIssue.START_FAILED, "비콘 설정 값이 올바르지 않습니다")
        }
        val adapter = BeaconPermissions.adapter(appContext)
            ?: return BeaconFailure(BeaconIssue.UNSUPPORTED, "이 기기는 Bluetooth를 지원하지 않습니다")
        val bleAdvertiser = try {
            // Bluetooth may have been turned off between the preflight and now.
            if (!adapter.isEnabled) return BeaconFailure(BeaconIssue.BLUETOOTH_OFF, "Bluetooth가 꺼져 있습니다")
            adapter.bluetoothLeAdvertiser
        } catch (_: SecurityException) {
            return BeaconFailure(BeaconIssue.PERMISSIONS, "BLE 송신 권한이 없습니다")
        }
        if (bleAdvertiser == null) {
            return BeaconFailure(BeaconIssue.UNSUPPORTED, "이 기기는 BLE 송신을 지원하지 않습니다")
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val primary = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(serviceUuid, BeaconProtocol.encode(config))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(serviceUuid)
            .build()

        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                if (callback === this) onStarted()
            }

            override fun onStartFailure(errorCode: Int) {
                if (callback !== this) return
                callback = null
                advertiser = null
                onFailed(advertiseFailure(errorCode))
            }
        }
        callback = advertiseCallback
        advertiser = bleAdvertiser
        return try {
            bleAdvertiser.startAdvertising(settings, primary, scanResponse, advertiseCallback)
            null
        } catch (_: SecurityException) {
            clearHandles()
            BeaconFailure(BeaconIssue.PERMISSIONS, "BLE 송신 권한이 없습니다")
        } catch (_: IllegalStateException) {
            clearHandles()
            BeaconFailure(BeaconIssue.BLUETOOTH_OFF, "Bluetooth가 꺼져 있습니다")
        } catch (_: RuntimeException) {
            clearHandles()
            BeaconFailure(BeaconIssue.ADVERTISE_FAILED, "BLE 송신을 시작하지 못했습니다")
        }
    }

    fun stop() {
        val activeAdvertiser = advertiser
        val activeCallback = callback
        clearHandles()
        if (activeAdvertiser != null && activeCallback != null) {
            try {
                activeAdvertiser.stopAdvertising(activeCallback)
            } catch (_: SecurityException) {
                // Permission revoked: advertising is gone either way.
            } catch (_: RuntimeException) {
                // Bluetooth already off: advertising is gone either way.
            }
        }
    }

    private fun clearHandles() {
        callback = null
        advertiser = null
    }

    private fun advertiseFailure(errorCode: Int): BeaconFailure = when (errorCode) {
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
            BeaconFailure(BeaconIssue.UNSUPPORTED, "이 기기는 BLE 송신을 지원하지 않습니다")
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
            BeaconFailure(BeaconIssue.ADVERTISE_FAILED, "사용 가능한 BLE 광고 슬롯이 없습니다 (오류 $errorCode)")
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
            BeaconFailure(BeaconIssue.ADVERTISE_FAILED, "BLE 광고 데이터가 너무 큽니다 (오류 $errorCode)")
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED ->
            BeaconFailure(BeaconIssue.ADVERTISE_FAILED, "BLE 광고가 이미 실행 중입니다 (오류 $errorCode)")
        else ->
            BeaconFailure(BeaconIssue.ADVERTISE_FAILED, "BLE 송신에 실패했습니다 (오류 $errorCode)")
    }
}

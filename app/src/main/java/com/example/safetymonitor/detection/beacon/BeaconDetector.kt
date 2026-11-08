package com.example.safetymonitor.detection.beacon

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock

/** A synchronous or asynchronous radio failure, already translated to a user-facing Korean message. */
internal data class BeaconFailure(val issue: BeaconIssue, val message: String)

/**
 * Real BLE receiver. Scans for the WORK SAFE service UUID, re-validates the ServiceData payload of
 * every result, and feeds only genuine RSSI samples into [BeaconPositionEstimator].
 *
 * Scan callbacks are delivered on the main looper; the estimator is additionally guarded by a lock
 * because the service reads snapshots from its timer. One instance = one scan session.
 */
class BeaconDetector(context: Context) {
    private val appContext = context.applicationContext
    private val estimator = BeaconPositionEstimator()
    private val serviceUuid = ParcelUuid.fromString(BeaconProtocol.SERVICE_UUID)

    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null

    val isScanning: Boolean get() = callback != null

    /**
     * Starts a filtered scan. Returns null when the framework accepted the request; asynchronous
     * failures are reported once through [onFailure]. Scanning is never retried here.
     */
    internal fun start(onFailure: (BeaconFailure) -> Unit): BeaconFailure? {
        if (callback != null) return null
        val bleScanner = try {
            BeaconPermissions.adapter(appContext)?.bluetoothLeScanner
        } catch (_: SecurityException) {
            return BeaconFailure(BeaconIssue.PERMISSIONS, "BLE 스캔 권한이 없습니다")
        }
        if (bleScanner == null) return BeaconFailure(BeaconIssue.BLUETOOTH_OFF, "Bluetooth가 꺼져 있습니다")

        // Service-UUID filter only: 128-bit ServiceData prefix offload is unreliable on older stacks,
        // so ServiceData is validated in software in handleResults().
        val filters = listOf(ScanFilter.Builder().setServiceUuid(serviceUuid).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (callback === this) handleResults(listOf(result))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (callback === this) handleResults(results)
            }

            override fun onScanFailed(errorCode: Int) {
                if (callback !== this) return
                // The scan is dead; drop the handle without calling stopScan on a failed registration.
                callback = null
                scanner = null
                onFailure(scanFailure(errorCode))
            }
        }
        callback = scanCallback
        scanner = bleScanner
        return try {
            bleScanner.startScan(filters, settings, scanCallback)
            null
        } catch (_: SecurityException) {
            clearHandles()
            BeaconFailure(BeaconIssue.PERMISSIONS, "BLE 스캔 권한이 없습니다")
        } catch (_: IllegalStateException) {
            clearHandles()
            BeaconFailure(BeaconIssue.BLUETOOTH_OFF, "Bluetooth가 꺼져 있습니다")
        } catch (_: RuntimeException) {
            clearHandles()
            BeaconFailure(BeaconIssue.SCAN_FAILED, "BLE 스캔을 시작하지 못했습니다")
        }
    }

    fun stop() {
        val activeScanner = scanner
        val activeCallback = callback
        clearHandles()
        if (activeScanner != null && activeCallback != null) {
            try {
                activeScanner.stopScan(activeCallback)
            } catch (_: SecurityException) {
                // Permission revoked: the scan is gone either way.
            } catch (_: RuntimeException) {
                // Bluetooth already off: the scan is gone either way.
            }
        }
        synchronized(estimator) { estimator.clear() }
    }

    /** Current expiry-aware view of the real samples collected so far. */
    fun snapshot(nowElapsedMillis: Long = SystemClock.elapsedRealtime()): BeaconEstimate =
        synchronized(estimator) { estimator.snapshot(nowElapsedMillis) }

    private fun clearHandles() {
        callback = null
        scanner = null
    }

    private fun handleResults(results: List<ScanResult>) {
        if (results.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        // Batches are not guaranteed to be chronological; the estimator rejects out-of-order samples.
        val ordered = if (results.size > 1) results.sortedBy { it.timestampNanos } else results
        synchronized(estimator) {
            for (result in ordered) {
                val rssi = result.rssi
                if (rssi < -127 || rssi > -1) continue // 127 = "RSSI not available"; never use as a sample
                val config = BeaconProtocol.decode(result.scanRecord?.getServiceData(serviceUuid)) ?: continue
                // timestampNanos is on the elapsedRealtimeNanos clock, same base as elapsedRealtime().
                val observedAt = (result.timestampNanos / 1_000_000L).coerceAtMost(now)
                estimator.observe(config, rssi, observedAt, now)
            }
        }
    }

    private fun scanFailure(errorCode: Int): BeaconFailure = when (errorCode) {
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ->
            BeaconFailure(BeaconIssue.UNSUPPORTED, "이 기기는 필요한 BLE 스캔 기능을 지원하지 않습니다")
        ScanCallback.SCAN_FAILED_ALREADY_STARTED ->
            BeaconFailure(BeaconIssue.SCAN_FAILED, "BLE 스캔이 이미 실행 중입니다 (오류 $errorCode)")
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES ->
            BeaconFailure(BeaconIssue.SCAN_FAILED, "BLE 하드웨어 자원이 부족합니다 (오류 $errorCode)")
        ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
            BeaconFailure(BeaconIssue.SCAN_FAILED, "BLE 스캔을 너무 자주 시작했습니다. 잠시 후 다시 시도하세요 (오류 $errorCode)")
        else ->
            BeaconFailure(BeaconIssue.SCAN_FAILED, "BLE 스캔에 실패했습니다 (오류 $errorCode)")
    }
}

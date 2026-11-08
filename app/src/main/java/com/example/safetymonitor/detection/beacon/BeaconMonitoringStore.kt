package com.example.safetymonitor.detection.beacon

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local BLE state. [BeaconMonitoringService] (and its start/stop helpers) is the only writer;
 * screens only observe. Nothing is restored from disk: a fresh process always starts as IDLE.
 */
object BeaconMonitoringStore {
    private val mutableState = MutableStateFlow(BeaconSnapshot())
    val state: StateFlow<BeaconSnapshot> = mutableState.asStateFlow()

    internal fun publish(snapshot: BeaconSnapshot) {
        mutableState.value = snapshot
    }
}

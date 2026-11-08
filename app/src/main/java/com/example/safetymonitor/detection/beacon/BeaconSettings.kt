package com.example.safetymonitor.detection.beacon

import android.content.Context

/** Persists only the transmitter configuration. Whether the transmitter is active is never stored. */
object BeaconSettings {
    private const val PREFERENCES = "beacon_transmitter_settings"
    private const val KEY_ID = "id"
    private const val KEY_X_CM = "x_cm"
    private const val KEY_Y_CM = "y_cm"
    private const val KEY_REFERENCE_RSSI = "reference_rssi"

    /** Returns the saved config, or the default config if nothing valid was saved. */
    fun load(context: Context): BeaconConfig {
        val defaults = BeaconConfig()
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val config = BeaconConfig(
            id = preferences.getInt(KEY_ID, defaults.id),
            xCm = preferences.getInt(KEY_X_CM, defaults.xCm),
            yCm = preferences.getInt(KEY_Y_CM, defaults.yCm),
            referenceRssi = preferences.getInt(KEY_REFERENCE_RSSI, defaults.referenceRssi)
        )
        return if (BeaconProtocol.isValid(config)) config else defaults
    }

    /** Invalid configs are ignored so a corrupt value can never be broadcast later. */
    fun save(context: Context, config: BeaconConfig) {
        if (!BeaconProtocol.isValid(config)) return
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ID, config.id)
            .putInt(KEY_X_CM, config.xCm)
            .putInt(KEY_Y_CM, config.yCm)
            .putInt(KEY_REFERENCE_RSSI, config.referenceRssi)
            .apply()
    }
}

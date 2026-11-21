package com.example.safetymonitor.watch

import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

/** Google Play services verifies the peer package and signing certificate. */
class WatchListenerService : WearableListenerService() {
    private val transport by lazy { WearWatchTransport(applicationContext) }

    override fun onDataChanged(events: DataEventBuffer) {
        val target = WatchDataRepository.capture()
        for (event in events) {
            val item = event.dataItem
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val node = item.uri.watchOrigin() ?: continue
            val values = item.readSummaryValues() ?: continue
            WatchDataRepository.receiveBlocking(transport, target, node, values)
        }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.name != WatchSummary.CAPABILITY) return
        val target = WatchDataRepository.capture()
        WatchDataRepository.refreshBlocking(transport, target)
    }
}

package com.example.safetymonitor.sos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread state for receipt sound only; never modifies the server event. */
object ReceiptAlarmStore {
    private val current = MutableStateFlow<String?>(null)
    val active = current.asStateFlow()
    internal var onSilence: (() -> Unit)? = null
    private val label = MutableStateFlow("낙상")
    val reason = label.asStateFlow()
    internal fun show(id: String, reason: String = "낙상") { label.value = reason; current.value = id }
    internal fun clear() { current.value = null }
    fun silence(id: String, code: String) {
        if (code == "0000" && current.value == id) onSilence?.invoke()
    }
}

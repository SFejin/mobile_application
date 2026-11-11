package com.example.safetymonitor.sos

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object SosStore {
    private val mutable = MutableStateFlow(SosState())
    val state = mutable.asStateFlow()
    private val connection = MutableStateFlow<SosSession?>(null)
    val session = connection.asStateFlow()
    private var loaded = false

    fun initialize(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences("sos_incident", Context.MODE_PRIVATE)
        val saved = SosState(
            phase = runCatching { SosPhase.valueOf(p.getString("phase", "IDLE")!!) }.getOrDefault(SosPhase.IDLE),
            incidentId = p.getString("id", "")!!,
            occurredAt = p.getLong("time", 0),
            workerId = p.getLong("worker", -1).takeIf { it > 0 },
            server = p.getString("server", "")!!,
            test = p.getBoolean("test", false),
            message = p.getString("message", SosState().message)!!
        )
        publish(context, SosStateMachine.recover(saved))
    }

    fun publish(context: Context, state: SosState) {
        val previous = mutable.value
        mutable.value = state
        // Persist transitions, not every countdown tick. A SENDING record precedes network I/O.
        if (previous.phase != state.phase || previous.incidentId != state.incidentId) {
            context.getSharedPreferences("sos_incident", Context.MODE_PRIVATE).edit()
                .putString("phase", state.phase.name).putString("id", state.incidentId)
                .putLong("time", state.occurredAt).putLong("worker", state.workerId ?: -1)
                .putString("server", state.server).putBoolean("test", state.test)
                .putString("message", state.message).commit()
        }
    }

    fun connect(value: SosSession?) { connection.value = value }
}

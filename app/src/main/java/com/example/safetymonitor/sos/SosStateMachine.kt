package com.example.safetymonitor.sos

enum class SosPhase { IDLE, COUNTDOWN, SENDING, SENT, CANCELLED, FAILED, UNKNOWN, TEST_DONE }

data class SosState(
    val phase: SosPhase = SosPhase.IDLE,
    val incidentId: String = "",
    val occurredAt: Long = 0,
    val deadline: Long = 0,
    val remainingSeconds: Int = 0,
    val workerId: Long? = null,
    val server: String = "",
    val test: Boolean = false,
    val message: String = "낙상 감지 후 20초 내 취소하지 않으면 서버로 전송합니다."
) {
    val active get() = phase == SosPhase.COUNTDOWN || phase == SosPhase.SENDING
}

/** Main-thread owned. Monotonic time controls countdown; wall time is only event metadata. */
class SosStateMachine(initial: SosState = SosState()) {
    var state = initial
        private set
    private var lastFinished: Long? = null

    fun detect(id: String, now: Long, wallTime: Long, workerId: Long?, server: String, test: Boolean): Boolean {
        if (state.active || lastFinished?.let { now - it < 30_000 } == true) return false
        state = SosState(SosPhase.COUNTDOWN, id, wallTime, now + 20_000, 20, workerId, server, test,
            if (test) "연습 알람입니다. 서버로 전송하지 않습니다." else "낙상이 감지되었습니다. 괜찮으면 전송을 취소하세요.")
        return true
    }

    fun cancel(id: String, now: Long): Boolean {
        if (state.phase != SosPhase.COUNTDOWN || state.incidentId != id) return false
        state = state.copy(phase = SosPhase.CANCELLED, remainingSeconds = 0, message = "SOS 전송을 취소했습니다.")
        lastFinished = now
        return true
    }

    /** True exactly once, when this incident should be dispatched. */
    fun tick(now: Long): Boolean {
        if (state.phase != SosPhase.COUNTDOWN) return false
        val remaining = ((state.deadline - now + 999) / 1000).coerceAtLeast(0).toInt()
        state = state.copy(remainingSeconds = remaining)
        if (now < state.deadline) return false
        state = state.copy(phase = SosPhase.SENDING, message = "서버에 SOS를 전송하고 있습니다.")
        return true
    }

    fun finish(id: String, phase: SosPhase, message: String, now: Long): Boolean {
        require(phase in setOf(SosPhase.SENT, SosPhase.FAILED, SosPhase.UNKNOWN, SosPhase.TEST_DONE))
        if (state.incidentId != id || state.phase != SosPhase.SENDING) return false
        state = state.copy(phase = phase, message = message)
        lastFinished = now
        return true
    }

    companion object {
        fun recover(saved: SosState): SosState = when (saved.phase) {
            SosPhase.COUNTDOWN -> saved.copy(phase = SosPhase.FAILED, remainingSeconds = 0,
                message = "앱 프로세스 종료로 SOS 대기가 중단되었습니다. 전송하지 않았습니다.")
            SosPhase.SENDING -> saved.copy(phase = SosPhase.UNKNOWN,
                message = "전송 중 앱이 종료되었습니다. 관리자 화면에서 접수 여부를 확인하세요.")
            else -> saved
        }
    }
}

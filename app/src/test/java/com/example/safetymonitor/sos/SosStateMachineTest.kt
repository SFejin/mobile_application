package com.example.safetymonitor.sos

import org.junit.Assert.*
import org.junit.Test

class SosStateMachineTest {
    private fun pending(test: Boolean = false) = SosStateMachine().apply {
        assertTrue(detect("a", 1000, 123456, 7, "http://server:8081", test))
    }

    @Test fun waitsTwentySecondsAndDispatchesExactlyOnce() {
        val machine = pending()
        assertFalse(machine.tick(20_999))
        assertEquals(1, machine.state.remainingSeconds)
        assertTrue(machine.tick(21_000))
        assertFalse(machine.tick(21_001))
        assertEquals(123456L, machine.state.occurredAt)
        assertEquals(SosPhase.SENDING, machine.state.phase)
    }

    @Test fun cancellationPreventsSendingEvenAfterDeadline() {
        val machine = pending()
        assertTrue(machine.cancel("a", 2000))
        assertFalse(machine.tick(30_000))
        assertEquals(SosPhase.CANCELLED, machine.state.phase)
    }

    @Test fun oldNotificationCannotCancelNewIncident() {
        val machine = pending()
        assertFalse(machine.cancel("old", 2000))
        assertEquals(SosPhase.COUNTDOWN, machine.state.phase)
    }

    @Test fun repeatedDetectionCannotExtendDeadlineOrChangeRecipient() {
        val machine = pending()
        assertFalse(machine.detect("b", 20_000, 999999, 9, "http://other", false))
        assertEquals(7L, machine.state.workerId)
        assertEquals(21_000L, machine.state.deadline)
    }

    @Test fun sendingCannotBeCancelledOrReplaced() {
        val machine = pending()
        machine.tick(21_000)
        assertFalse(machine.cancel("a", 21_001))
        assertFalse(machine.detect("b", 60_000, 999, 8, "http://other", false))
    }

    @Test fun responseForDifferentIncidentIsIgnored() {
        val machine = pending()
        machine.tick(21_000)
        assertFalse(machine.finish("old", SosPhase.SENT, "ok", 22_000))
        assertEquals(SosPhase.SENDING, machine.state.phase)
    }

    @Test fun terminalResultHasCooldownThenAllowsNewFall() {
        val machine = pending()
        machine.tick(21_000)
        assertTrue(machine.finish("a", SosPhase.UNKNOWN, "unknown", 22_000))
        assertFalse(machine.detect("b", 51_999, 999, 7, "http://server", false))
        assertTrue(machine.detect("b", 52_000, 999, 7, "http://server", false))
    }

    @Test fun processDeathDoesNotResendAnUncertainRequest() {
        val machine = pending()
        machine.tick(21_000)
        val recovered = SosStateMachine(SosStateMachine.recover(machine.state))
        assertEquals(SosPhase.UNKNOWN, recovered.state.phase)
        assertFalse(recovered.tick(99_999))
    }

    @Test fun interruptedCountdownIsNotSilentlyCompleted() {
        val recovered = SosStateMachine(SosStateMachine.recover(pending().state))
        assertEquals(SosPhase.FAILED, recovered.state.phase)
        assertFalse(recovered.tick(99_999))
    }

    @Test fun testAndRecipientAreCapturedAtDetection() {
        val machine = pending(test = true)
        assertTrue(machine.state.test)
        machine.tick(21_000)
        machine.finish("a", SosPhase.TEST_DONE, "local only", 22_000)
        assertEquals(SosPhase.TEST_DONE, machine.state.phase)
        assertTrue(machine.state.test)
    }
}

package com.unitrading.app

import com.unitrading.broker.CommandOutcome
import com.unitrading.broker.Side
import kotlin.test.*

class CopyTradeCommandGateTest {
    private val adjustment = CopyAdjustment("follower", Side.BUY, 2, 2)

    @Test fun acceptanceDoesNotPermitAnotherOrderUntilTheActualFillIsConfirmed() {
        val gate = CopyTradeCommandGate()
        assertTrue(gate.begin("a", adjustment))
        assertFalse(gate.begin("b", adjustment))
        gate.response("a", CommandOutcome.ACCEPTED)
        assertIs<CopyGateState.AwaitingFill>(gate.state)
        assertFalse(gate.begin("b", adjustment))
        gate.confirmed("a", "follower", 2, finalFill = true)
        assertIs<CopyGateState.Ready>(gate.state)
        assertFalse(gate.begin("a", adjustment))
        assertTrue(gate.begin("b", adjustment))
    }

    @Test fun rejectionUnknownPartialFillAndWrongReceiptStopTheGroup() {
        for (outcome in listOf(CommandOutcome.REJECTED, CommandOutcome.UNKNOWN)) {
            val gate = CopyTradeCommandGate()
            gate.begin("a", adjustment)
            gate.response("a", outcome)
            assertIs<CopyGateState.Stopped>(gate.state)
            assertFalse(gate.begin("b", adjustment))
        }
        val partial = CopyTradeCommandGate()
        partial.begin("a", adjustment)
        partial.response("a", CommandOutcome.ACCEPTED)
        partial.confirmed("a", "follower", 1, finalFill = false)
        assertIs<CopyGateState.Stopped>(partial.state)
        val wrong = CopyTradeCommandGate()
        wrong.begin("a", adjustment)
        wrong.response("different", CommandOutcome.ACCEPTED)
        assertIs<CopyGateState.Stopped>(wrong.state)
    }

    @Test fun disconnectWhileWaitingNeverRearmsAutomatically() {
        val gate = CopyTradeCommandGate()
        gate.begin("a", adjustment)
        gate.response("a", CommandOutcome.ACCEPTED)
        gate.stop("连接已断开")
        gate.confirmed("a", "follower", 2, finalFill = true)
        assertIs<CopyGateState.Stopped>(gate.state)
        assertFalse(gate.begin("b", adjustment))
    }
}

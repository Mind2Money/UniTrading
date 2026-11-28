package com.unitrading.app

import kotlin.test.*

class CopyFollowerIsolationTest {
    private val running = mapOf("one" to CopyFollowerRunStatus(CopyFollowerRunState.RUNNING), "two" to CopyFollowerRunStatus(CopyFollowerRunState.RUNNING))
    @Test fun failedFollowerDoesNotStopOtherAccounts() {
        val result = CopyFollowerIsolation.fail(running, "one", "订单结果未知")
        assertEquals(CopyFollowerRunState.NEEDS_REVIEW, result.getValue("one").state)
        assertEquals(CopyFollowerRunState.RUNNING, result.getValue("two").state)
    }
    @Test fun reconnectOnlyResumesAccountsWithCompleteReconciliation() {
        val paused = CopyFollowerIsolation.disconnected(CopyFollowerIsolation.fail(running, "one", "订单结果未知"))
        val result = CopyFollowerIsolation.reconciled(paused, CopyRecoveryResult(null, mapOf("one" to "待核对", "two" to null)))
        assertEquals(CopyFollowerRunState.NEEDS_REVIEW, result.getValue("one").state)
        assertEquals(CopyFollowerRunState.RUNNING, result.getValue("two").state)
    }
    @Test fun sourceUnknownOrMissingFollowerBlocksRecovery() {
        val paused = CopyFollowerIsolation.afterRestart(running)
        assertTrue(CopyFollowerIsolation.reconciled(paused, CopyRecoveryResult("主账户未确认", mapOf("one" to null, "two" to null)))
            .values.all { it.state == CopyFollowerRunState.WAITING_FOR_RECONNECT })
        val result = CopyFollowerIsolation.reconciled(paused, CopyRecoveryResult(null, mapOf("one" to null)))
        assertEquals(CopyFollowerRunState.RUNNING, result.getValue("one").state)
        assertEquals(CopyFollowerRunState.WAITING_FOR_RECONNECT, result.getValue("two").state)
    }
}

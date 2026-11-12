package com.unitrading.ui

import com.unitrading.broker.*
import kotlin.test.*

class ProtectionEditPolicyTest {
    private val position = Position("p", "MES", Side.BUY, 100, 5500, 0, 5400, 5600, 0)
    private val remote = BrokerCapabilities(addProtection = false, removeProtection = false, atomicProtectionEdit = false)
    @Test fun restrictedBrokerRejectsRemovalAndTwoLegEditsBeforeSending() {
        assertNotNull(protectionEditError(remote, position, null, 5600))
        assertNotNull(protectionEditError(remote, position, 5300, 5700))
        assertNotNull(protectionEditError(remote, position.copy(takeProfit = null), 5400, 5700))
        assertNull(protectionEditError(remote, position, 5300, 5600))
    }
    @Test fun supportedBrokerRetainsAtomicAddRemoveAndNoOpIsDisabled() {
        val full = BrokerCapabilities()
        assertNull(protectionEditError(full, position, null, null))
        assertNull(protectionEditError(full, position, 5300, 5700))
        assertNotNull(protectionEditError(full, position, 5400, 5600))
        assertNotNull(protectionEditError(full, position.copy(protectionEditable = false), 5300, 5600))
    }
    @Test fun pendingEntryProtectionFollowsTheSameRulesWhileTheEntryIsEditable() {
        val order = PendingOrder("o", OrderRequest("MES", Side.BUY, OrderKind.LIMIT, 100, 5500, 5400, 5600), 0)
        assertNull(pendingProtectionEditError(remote, order, 5300, 5600))
        assertNotNull(pendingProtectionEditError(remote, order, 5300, 5700))
        assertNotNull(pendingProtectionEditError(remote, order, null, 5600))
        assertNotNull(pendingProtectionEditError(remote, order.copy(request = order.request.copy(stopLoss = null)), 5300, 5600))
        assertNotNull(pendingProtectionEditError(BrokerCapabilities(), order, 5400, 5600))
        assertNull(pendingProtectionEditError(BrokerCapabilities(), order, null, 5700))
        for (locked in listOf(order.copy(protectionEditable = false), order.copy(editable = false), order.copy(role = OrderRole.STOP_LOSS))) {
            assertNotNull(pendingProtectionEditError(BrokerCapabilities(), locked, 5300, 5600))
        }
        assertNotNull(pendingProtectionEditError(BrokerCapabilities(modifyPendingProtection = false), order, 5300, 5600))
    }
    /** Tradovate and Rithmic: plain stop and limit orders paired by OCO, added after entry and moved one line at a time. */
    @Test fun ocoBrokersAddLinesAfterEntryAndPlaceAPairInOneStep() {
        val oco = remote.copy(ocoProtection = true)
        val bare = position.copy(stopLoss = null, takeProfit = null)
        assertNull(protectionEditError(oco, bare, 5400, null))
        assertNull(protectionEditError(oco, bare, 5400, 5600), "both lines go out as one OCO pair")
        assertNull(protectionEditError(oco, bare.copy(stopLoss = 5400), 5300, 5600), "the pair is placed anew, the kept line at its new price")
        assertNotNull(protectionEditError(oco, position, 5300, 5700), "two existing lines still move one at a time")
        assertNotNull(protectionEditError(oco, position, null, 5600), "a line is taken away by cancelling its order")
        assertNotNull(protectionEditError(remote, bare, 5400, null), "without the capability nothing is added")
        val order = PendingOrder("o", OrderRequest("MES", Side.BUY, OrderKind.LIMIT, 100, 5500, null, null), 0)
        assertNotNull(pendingProtectionEditError(oco, order, 5400, null), "a pending entry takes no new bracket")
        assertNotNull(pendingProtectionEditError(oco, order.copy(request = order.request.copy(stopLoss = 5400)), 5300, 5600),
            "a pending entry's brackets are not an OCO placement")
    }
    @Test fun theAddNoteSaysWhatGoesOutAndWhenThePositionGoesWithoutALine() {
        val oco = remote.copy(ocoProtection = true)
        val bare = position.copy(stopLoss = null, takeProfit = null)
        assertNull(protectionAddNote(remote, bare, 5400, 5600))
        assertNull(protectionAddNote(oco, position, 5300, 5600), "a move adds nothing")
        assertTrue("OCO" in protectionAddNote(oco, bare, 5400, 5600)!!)
        assertTrue("Stop" in protectionAddNote(oco, bare, 5400, null)!!)
        assertTrue("Limit" in protectionAddNote(oco, bare, null, 5600)!!)
        val replacing = protectionAddNote(oco, bare.copy(stopLoss = 5400), 5400, 5600)!!
        assertTrue("先撤销现有止损" in replacing && "没有这条止损" in replacing, replacing)
    }
}

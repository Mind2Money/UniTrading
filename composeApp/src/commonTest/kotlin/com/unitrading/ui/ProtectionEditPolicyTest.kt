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
}

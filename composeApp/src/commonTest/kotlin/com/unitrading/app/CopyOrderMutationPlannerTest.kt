package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class CopyOrderMutationPlannerTest {
    private val link = CopyPendingLink("leader-order", "follower-order", "follower")
    private val order = PendingOrder("follower-order", OrderRequest("MESZ6", Side.BUY, OrderKind.LIMIT, 100, entryPrice = 5000), 0, version = 3)

    @Test fun usesOnlyTheLinkedFollowerOrderAndItsCurrentVersion() {
        assertEquals(CopyOrderMutation.ChangePrice("follower-order", 3, 4999),
            CopyOrderMutationPlanner.changePrice(link, "leader-order", "follower", order, 4999))
        assertEquals(CopyOrderMutation.Cancel("follower-order"), CopyOrderMutationPlanner.cancel(link, "leader-order", "follower", order))
    }

    @Test fun missingWrongOrPartiallyFilledFollowerOrderDoesNotCreateAnotherMutation() {
        assertIs<CopyOrderMutation.Stop>(CopyOrderMutationPlanner.cancel(link, "leader-order", "follower", null))
        assertIs<CopyOrderMutation.Stop>(CopyOrderMutationPlanner.cancel(link, "leader-order", "other", order))
        assertIs<CopyOrderMutation.Stop>(CopyOrderMutationPlanner.changePrice(link, "leader-order", "follower", order.copy(filledLotsHundredths = 100), 4999))
        assertIs<CopyOrderMutation.Stop>(CopyOrderMutationPlanner.cancel(link, "leader-order", "follower", order.copy(role = OrderRole.UNRESOLVED)))
    }
}

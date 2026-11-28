package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class CopyOrderMutationPlannerTest {
    private val link = CopyPendingLink("leader-order", "follower-order", "follower")
    private val order = PendingOrder("follower-order", OrderRequest("MESZ6", Side.BUY, OrderKind.LIMIT, 100, entryPrice = 5000), 0, version = 3)
    private val changedSource = order.copy(id = "leader-order", request = order.request.copy(entryPrice = 4999))
    private val cancelledSource = CopyOrderLifecycle("leader-order", "Canceled", 4, 0)

    @Test fun usesOnlyTheLinkedFollowerOrderAndItsCurrentVersion() {
        assertEquals(CopyOrderMutation.ChangePrice("follower-order", 3, 4999),
            CopyOrderMutationPlanner.changePrice(link, "leader-order", "follower", changedSource, order, 4999, true))
        assertEquals(CopyOrderMutation.Cancel("follower-order"), CopyOrderMutationPlanner.cancel(link, "leader-order", "follower", cancelledSource, order, true))
    }

    @Test fun missingWrongOrPartiallyFilledFollowerOrderDoesNotCreateAnotherMutation() {
        assertIs<CopyOrderMutation.Stop>(CopyOrderMutationPlanner.cancel(link, "leader-order", "follower", cancelledSource, null, true))
        assertIs<CopyOrderMutation.Stop>(CopyOrderMutationPlanner.cancel(link, "leader-order", "other", cancelledSource, order, true))
        assertIs<CopyOrderMutation.Stop>(CopyOrderMutationPlanner.changePrice(link, "leader-order", "follower", changedSource, order.copy(filledLotsHundredths = 100), 4999, true))
        assertIs<CopyOrderMutation.Stop>(CopyOrderMutationPlanner.cancel(link, "leader-order", "follower", cancelledSource, order.copy(role = OrderRole.UNRESOLVED), true))
        assertIs<CopyOrderMutation.Stop>(CopyOrderMutationPlanner.cancel(link, "leader-order", "follower", cancelledSource.copy(status = "Filled"), order, true))
        assertIs<CopyOrderMutation.Stop>(CopyOrderMutationPlanner.cancel(link, "leader-order", "follower", cancelledSource, order, false))
    }

    @Test fun cancelMayRemoveTheRemainderOfAPartiallyFilledLinkedEntry() {
        val partial = order.copy(request = order.request.copy(lotsHundredths = 200), filledLotsHundredths = 100, editable = false)
        assertEquals(CopyOrderMutation.Cancel("follower-order"), CopyOrderMutationPlanner.cancel(link, "leader-order", "follower", cancelledSource, partial, true))
    }
}

package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class CopyStartAssessmentTest {
    private val config = CopySavedConfiguration("tradovate-demo-test", "leader", listOf(CopySavedFollower("follower")))
    private val descriptor = BrokerDescriptor(config.brokerId, "Demo", "Demo", environment = BrokerEnvironment.REMOTE_DEMO)
    private val catalog = BrokerCatalog(emptyList(), listOf(AccountInfo("leader", "Leader"), AccountInfo("follower", "Follower")))
    private fun snapshot(id: String) = AccountSnapshot(id, 0, copyEpoch = 1, copyRevision = 7,
        copyPositionsComplete = true, copyOrdersComplete = true, copyOrdersClear = true)
    private val accounts = mapOf("leader" to snapshot("leader"), "follower" to snapshot("follower"))
    private fun check(c: CopySavedConfiguration = config, d: BrokerDescriptor = descriptor, book: BrokerCatalog = catalog,
        m: MarketSnapshot = MarketSnapshot(), s: Map<String, AccountSnapshot> = accounts, unresolved: Boolean = false) =
        CopyStartAssessment.reason(c, d, book, m, s, unresolved)

    @Test fun allSelectedAccountsMustBeFlatOnOneCompleteBook() {
        assertNull(check())
        val p = Position("p", "MESZ6", Side.BUY, 100, 10_000, 0, marginMicros = 0)
        assertNotNull(check(s = accounts + ("follower" to snapshot("follower").copy(positions = listOf(p)))))
        assertNotNull(check(s = accounts + ("leader" to snapshot("leader").copy(copyOrdersClear = false))))
        assertNotNull(check(s = accounts + ("follower" to snapshot("follower").copy(copyOrdersComplete = false))))
        assertNotNull(check(s = accounts + ("follower" to snapshot("follower").copy(copyRevision = 8))))
        assertNotNull(check(s = accounts - "leader"))
    }
    @Test fun connectionAccountIdentityAndUnresolvedOperationsAreRequired() {
        assertNotNull(check(m = MarketSnapshot(connected = false)))
        assertNotNull(check(book = BrokerCatalog(emptyList(), listOf(AccountInfo("leader", "Leader")))))
        assertNotNull(check(c = config.copy(followers = listOf(CopySavedFollower("leader")))))
        assertNotNull(check(unresolved = true))
        assertNotNull(check(d = descriptor.copy(environment = BrokerEnvironment.LOCAL_DEMO)))
        assertNull(check(d = descriptor.copy(environment = BrokerEnvironment.LIVE)))
    }
}

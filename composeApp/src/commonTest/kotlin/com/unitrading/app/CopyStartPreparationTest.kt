package com.unitrading.app

import com.unitrading.broker.*
import kotlin.test.*

class CopyStartPreparationTest {
    private val accounts = listOf(AccountInfo("leader", "Leader"), AccountInfo("follower", "Follower"), AccountInfo("other", "Other"))
    private val catalog = BrokerCatalog(emptyList(), accounts)
    private fun book(id: String) = AccountSnapshot(id, 0, copyRevision = 7, copyEpoch = 1,
        copyPositionsComplete = true, copyOrdersComplete = true, copyOrdersClear = true)
    private val selected = listOf("leader", "follower")
    private val books = selected.associateWith(::book)
    private fun inspect(rows: Map<String, AccountSnapshot> = books, ids: List<String> = selected) =
        CopyStartPreparationInspector.inspect(ids, catalog, MarketSnapshot(), rows)

    @Test fun checksOnlySelectedAccountsAndListsTheirExposure() {
        assertIs<CopyStartPreparation.Ready>(inspect())
        val position = Position("position", "MESZ6", Side.BUY, 100, 10_000, 0, marginMicros = 0)
        val pending = PendingOrder("order", OrderRequest("MNQZ6", Side.BUY, OrderKind.LIMIT, 100, 20_000), 0)
        val changed = books + ("leader" to book("leader").copy(positions = listOf(position))) +
            ("follower" to book("follower").copy(orders = listOf(pending), copyOrdersClear = false)) +
            ("other" to book("other").copy(positions = listOf(position)))
        val result = assertIs<CopyStartPreparation.NeedsCleanup>(inspect(changed))
        assertEquals(listOf("leader", "follower"), result.accounts.map { it.account.id })
        assertEquals(listOf(1, 0), result.accounts.map { it.positions })
        assertEquals(listOf(0, 1), result.accounts.map { it.orders })
    }
    @Test fun incompleteUnknownOrInconsistentSnapshotsNeverLookEmpty() {
        assertIs<CopyStartPreparation.Blocked>(inspect(books - "leader"))
        assertIs<CopyStartPreparation.Blocked>(inspect(books + ("leader" to book("leader").copy(copyOrdersClear = false))))
        assertIs<CopyStartPreparation.Blocked>(inspect(books + ("follower" to book("follower").copy(copyPositionsComplete = false))))
        assertIs<CopyStartPreparation.Blocked>(inspect(books + ("follower" to book("follower").copy(copyRevision = 8))))
        assertIs<CopyStartPreparation.Blocked>(inspect(ids = listOf("leader", "leader")))
    }
}

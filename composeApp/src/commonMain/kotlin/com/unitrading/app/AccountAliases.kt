package com.unitrading.app

import com.unitrading.broker.AccountInfo
import com.unitrading.broker.BrokerDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Names the user gave logins and trading accounts on this device, shown in place of the broker's names. [logins] is
 * keyed by login (the broker's descriptor id); [accounts] by login and then account id, because an account id is only
 * unique within its login.
 */
@Serializable
data class AccountAliases(
    val logins: Map<String, String> = emptyMap(),
    val accounts: Map<String, Map<String, String>> = emptyMap(),
) {
    fun login(broker: BrokerDescriptor): String = logins[broker.id] ?: broker.name
    fun account(brokerId: String, account: AccountInfo): String = accounts[brokerId]?.get(account.id) ?: account.name

    val valid: Boolean get() = (logins.values + accounts.values.flatMap { it.values })
        .all { it.isNotBlank() && it == it.trim() && it.length <= MAX_LENGTH }

    companion object { const val MAX_LENGTH = 30 }
}

/** Saved under [KEY] on this device only; nothing reaches the broker. A blank alias removes it and the broker's name shows again. */
class AccountAliasesController(private val store: SnapshotStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    val error = MutableStateFlow<String?>(null)
    private val current = MutableStateFlow(try {
        (store.read(KEY)?.let { json.decodeFromString<AccountAliases>(it) } ?: AccountAliases()).also { check(it.valid) }
    } catch (_: Exception) {
        error.value = "账户别名无法读取，已显示券商的原名。"
        AccountAliases()
    })
    val aliases = current.asStateFlow()

    suspend fun setLogin(brokerId: String, alias: String): Boolean = update { it.copy(logins = it.logins.with(brokerId, alias)) }

    suspend fun setAccount(brokerId: String, accountId: String, alias: String): Boolean = update {
        val named = (it.accounts[brokerId] ?: emptyMap()).with(accountId, alias)
        it.copy(accounts = if (named.isEmpty()) it.accounts - brokerId else it.accounts + (brokerId to named))
    }

    private fun Map<String, String>.with(key: String, alias: String): Map<String, String> =
        alias.trim().let { name -> if (name.isEmpty()) this - key else this + (key to name) }

    /** Saves first; a refused or failed save leaves the aliases as they were. */
    private suspend fun update(change: (AccountAliases) -> AccountAliases): Boolean = mutex.withLock {
        val next = change(current.value)
        if (!next.valid) {
            error.value = "别名最多 ${AccountAliases.MAX_LENGTH} 个字。"
            return@withLock false
        }
        try {
            withContext(Dispatchers.Default) { store.write(KEY, json.encodeToString(next)) }
            current.value = next
            error.value = null
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error.value = "账户别名保存失败，别名未变更。"; false }
    }

    companion object { const val KEY = "account-aliases-v1" }
}

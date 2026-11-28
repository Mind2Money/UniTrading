package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Read-only compatibility with a previously saved uncertain operation; it never places a trade. */
@Serializable
internal data class CopyLegacyRecoveryRecord(val brokerId: String, val accountIds: List<String>, val symbol: String, val requestId: String)

internal class CopyLegacyRecovery(private val registry: BrokerRegistry, private val store: SnapshotStore) {
    private val key = "copy-unconfirmed-v1"
    private val loaded = runCatching { store.read(key)?.takeIf(String::isNotBlank)?.let { Json.decodeFromString<CopyLegacyRecoveryRecord>(it) } }
    val unreadable = loaded.isFailure
    private val mutableRecovery = MutableStateFlow(loaded.getOrNull())
    val recovery = mutableRecovery.asStateFlow()

    /** Clear only by explicit user action, after all recorded accounts have been resynchronized and verified flat. */
    suspend fun clearRecovery(broker: BrokerPlugin): String? {
        if (unreadable) return "旧版跟单记录无法读取，请到券商核对账户"
        val record = recovery.value ?: return null
        if (record.brokerId != broker.descriptor.id || registry.plugins.none { it === broker }) return "请先连接原账户"
        try { broker.resynchronize() } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { return "账户同步失败，请核对后重试" }
        val snapshots = try { record.accountIds.associateWith { broker.openSession(it).state.value } }
        catch (_: Exception) { return "原账户不可用，请核对连接" }
        when (val state = CopyStartPreparationInspector.inspect(record.accountIds, broker.catalog.value, broker.market.value, snapshots)) {
            CopyStartPreparation.Ready -> Unit
            is CopyStartPreparation.NeedsCleanup -> return "原账户仍有仓位或挂单，请先逐一处理"
            is CopyStartPreparation.Blocked -> return state.reason
        }
        return try { store.write(key, ""); mutableRecovery.value = null; null }
        catch (_: Exception) { "无法保存核对结果，未清除待核对记录" }
    }
}

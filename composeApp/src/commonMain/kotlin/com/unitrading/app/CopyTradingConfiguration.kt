package com.unitrading.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal data class CopySavedFollower(val accountId: String, val fixedContracts: Int? = null, val multiplier: Int = 1, val maxContracts: Int = 1)
@Serializable
internal data class CopySavedConfiguration(val brokerId: String, val leaderId: String, val followers: List<CopySavedFollower>,
    val symbol: String? = null)

/** Local settings contain account IDs and sizing only, never credentials or an instruction to resume. */
internal class CopyTradingConfiguration(private val store: SnapshotStore) {
    private val key = "copy-trading-configuration-v1"
    private val codec = ListSerializer(CopySavedConfiguration.serializer())
    private val mutex = Mutex()
    private fun valid(config: CopySavedConfiguration): Boolean = config.brokerId.startsWith("tradovate-") && config.leaderId.isNotBlank() &&
        (config.symbol == null || config.symbol.isNotBlank() && !config.symbol.startsWith('@')) && config.followers.isNotEmpty() &&
        config.followers.map { it.accountId }.distinct().size == config.followers.size && config.followers.all {
            it.accountId.isNotBlank() && it.accountId != config.leaderId && it.multiplier in 1..100 && it.maxContracts in 1..1000 &&
                (it.fixedContracts == null || it.fixedContracts in 1..it.maxContracts)
        }
    private val loaded = runCatching {
        val rows = store.read(key)?.takeIf(String::isNotBlank)?.let { Json.decodeFromString(codec, it) }.orEmpty()
        require(rows.all(::valid) && rows.map { it.brokerId }.distinct().size == rows.size)
        rows
    }
    private val unreadable = loaded.isFailure
    private val mutableConfigurations = MutableStateFlow(loaded.getOrDefault(emptyList()).associateBy { it.brokerId })
    val configurations = mutableConfigurations.asStateFlow()

    /** Persist before reporting success; unreadable settings are never overwritten automatically. */
    suspend fun save(config: CopySavedConfiguration): Boolean = mutex.withLock {
        if (unreadable || !valid(config)) return@withLock false
        val next = mutableConfigurations.value + (config.brokerId to config)
        try {
            store.write(key, Json.encodeToString(codec, next.values.sortedBy { it.brokerId }))
            mutableConfigurations.value = next
            true
        } catch (_: Exception) { false }
    }
}

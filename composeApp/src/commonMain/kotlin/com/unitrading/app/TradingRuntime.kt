package com.unitrading.app

import com.unitrading.broker.BrokerRegistry
import com.unitrading.demo.DemoBroker
import com.unitrading.demo.DemoConfigs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

interface SnapshotStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

class MemorySnapshotStore : SnapshotStore {
    private val values = mutableMapOf<String, String>()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String) { values[key] = value }
}

class TradingRuntime(private val store: SnapshotStore, additionalPlugins: List<com.unitrading.broker.BrokerPlugin> = emptyList(), credentialStore: CredentialStore? = null) {
    val startupError = MutableStateFlow<String?>(null)
    val storageError = MutableStateFlow<String?>(null)
    val paused = MutableStateFlow(false)
    private val saveMutex = Mutex()
    val brokers: List<DemoBroker> = DemoConfigs.all.map { config ->
        try {
            DemoBroker(config, store.read(config.id))
        } catch (error: Exception) {
            startupError.value = "无法恢复 ${config.id}：${error.message}。原文件尚未覆盖。"
            DemoBroker(config, seedTrades = false)
        }
    }
    val registry = BrokerRegistry(brokers + additionalPlugins)
    val savedLogins = SavedTradovateAccounts(registry, credentialStore)

    suspend fun run() {
        var ticks = 0
        while (true) {
            delay(1_000)
            if (startupError.value == null && !paused.value) {
                brokers.forEach { it.advance(15) }
                if (++ticks % 5 == 0) save()
            }
        }
    }

    suspend fun save(): Boolean = saveMutex.withLock {
        if (startupError.value != null) return@withLock false
        val snapshots = brokers.associate { it.descriptor.id to it.exportState() }
        try {
            withContext(Dispatchers.Default) { snapshots.forEach { (key, value) -> store.write(key, value) } }
            storageError.value = null
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            storageError.value = "当前操作已在内存生效，但保存失败：${error.message}。请重试保存后再退出。"
            paused.value = true
            false
        }
    }

    suspend fun resetAll() {
        brokers.forEach { it.reset() }
        startupError.value = null
        storageError.value = null
        paused.value = false
        save()
    }
}

fun requestId(): String = Random.nextLong().toULong().toString(16) + Random.nextLong().toULong().toString(16)

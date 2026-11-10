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

class TradingRuntime(private val store: SnapshotStore, additionalPlugins: List<com.unitrading.broker.BrokerPlugin> = emptyList(), credentialStore: CredentialStore? = null, includeDemoBrokers: Boolean = false) {
    val startupError = MutableStateFlow<String?>(null)
    val storageError = MutableStateFlow<String?>(null)
    val paused = MutableStateFlow(false)
    private val saveMutex = Mutex()
    val theme = ThemeController(store)
    val tradingPreferences: TradingPreferencesController by lazy { TradingPreferencesController(store) }
    /** Interval favourites and custom intervals; read lazily so startup only touches the theme and demo snapshots. */
    val chartPreferences: ChartPreferencesController by lazy { ChartPreferencesController(store) }
    /** Desktop panel sizes are independent of chart preferences and broker/account state. */
    val desktopLayout: DesktopLayoutController by lazy { DesktopLayoutController(store) }
    val marketWatch: MarketWatchPreferences by lazy { MarketWatchPreferences(store) }
    val orderCapacity: OrderCapacityCache by lazy { OrderCapacityCache(store) }
    /** Chart indicator stack; also read lazily, when the first chart asks for it. */
    val indicators: IndicatorController by lazy { IndicatorController(store) }
    /** Chart drawings of every symbol, read when the first chart opens. */
    val drawings: com.unitrading.chart.drawing.ChartDrawings by lazy { com.unitrading.chart.drawing.ChartDrawings(store) }
    /** System notice (Android Live Update, iOS Live Activity) with the displayed account; the platform runs it with its presenter. */
    val livePnl: LivePnlController by lazy { LivePnlController(store) }
    val brokers: List<DemoBroker> = (if (includeDemoBrokers) DemoConfigs.all else emptyList()).map { config ->
        try {
            DemoBroker(config, store.read(config.id))
        } catch (error: Exception) {
            startupError.value = "无法恢复 ${config.id}：${error.message}。原文件尚未覆盖。"
            DemoBroker(config, seedTrades = false)
        }
    }
    val registry = BrokerRegistry(brokers + additionalPlugins)
    val savedLogins = SavedTradovateAccounts(registry, credentialStore, candleCacheStore = SnapshotCandleCacheStore(store)).also { it.allowRithmic = false }

    suspend fun run() {
        if (brokers.isEmpty()) return
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
            storageError.value = "操作已生效，但未能保存：${error.message}。请重试保存后再退出。"
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

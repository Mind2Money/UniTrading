package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlin.random.Random

internal object TradeSounds {
    const val entry = "files/sounds/entry.wav"
    const val takeProfit = "files/sounds/take-profit.wav"
    val stopLoss = (1..12).map { "files/sounds/stop-loss-${it.toString().padStart(2, '0')}.wav" }
    fun select(role: OrderRole, random: Random): String = when (role) {
        OrderRole.ENTRY -> entry
        OrderRole.TAKE_PROFIT -> takeProfit
        OrderRole.STOP_LOSS -> stopLoss.random(random)
        else -> error("Unresolved fill role")
    }
}

/** Returns after one playback; cancellation must stop and release the native player. */
interface TradeSoundPlayer {
    suspend fun play(resourcePath: String)
}

internal expect fun platformTradeSoundPlayer(): TradeSoundPlayer

/** One listener per plugin, independent of the displayed account, chart and recompositions. */
internal class TradeSoundController(private val player: TradeSoundPlayer, private val random: Random = Random.Default) {
    suspend fun run(registry: BrokerRegistry): Unit = coroutineScope {
        data class Queued(val plugin: BrokerPlugin, val fill: TradeFill)
        val queue = Channel<Queued>(Channel.UNLIMITED)
        val collectors = mutableMapOf<BrokerPlugin, Job>()
        val seen = mutableMapOf<BrokerPlugin, MutableSet<Pair<String, String>>>()
        val playback = launch {
            for ((plugin, fill) in queue) {
                if (registry.plugins.none { it === plugin }) continue
                try { withTimeoutOrNull(10_000) { player.play(TradeSounds.select(fill.role, random)) } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* A missing device or resource must not interrupt trading. */ }
            }
        }
        try {
            registry.state.collect { plugins ->
                for (removed in collectors.keys.filter { old -> plugins.none { it === old } }) {
                    collectors.remove(removed)?.cancelAndJoin()
                    seen.remove(removed)
                }
                for (plugin in plugins) if (plugin !in collectors) {
                    val notified = seen.getOrPut(plugin) { mutableSetOf() }
                    collectors[plugin] = launch(start = CoroutineStart.UNDISPATCHED) {
                        plugin.tradeFills.collect { fill ->
                            if (notified.add(fill.accountId to fill.orderId)) queue.send(Queued(plugin, fill))
                        }
                    }
                }
            }
        } finally {
            collectors.values.forEach { it.cancel() }
            playback.cancel()
            queue.close()
        }
    }
}

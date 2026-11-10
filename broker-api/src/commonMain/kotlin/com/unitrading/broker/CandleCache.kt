package com.unitrading.broker

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Non-secret market data; implementations should replace each value atomically. */
interface CandleCacheStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
}

@Serializable
private data class CachedCandles(val version: Int = 1, val candles: List<Candle>)

/** Per-broker candle persistence shared by the remote adapters; [maxBars] bounds what a series may hold. */
class CandleCache(private val store: CandleCacheStore?, private val namespace: String, private val maxBars: Int = 2000) {
    // Hex encoding is collision-free and keeps symbols such as @MES out of filesystem paths.
    private fun key(series: Pair<String, Timeframe>): String =
        "candles-v1-$namespace-${series.first.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }}-${series.second.id}"

    suspend fun read(series: Pair<String, Timeframe>, now: Long): List<Candle> = try {
        val text = store?.read(key(series))
        val cached = text?.let { Json.decodeFromString<CachedCandles>(it) }
        if (cached == null || cached.version != 1 || cached.candles.size > maxBars ||
            cached.candles.any { it.timeMs !in 0..now || it.low <= 0 || it.high < maxOf(it.open, it.close) ||
                it.low > minOf(it.open, it.close) || it.volume?.let { v -> v < 0 } == true } ||
            cached.candles.zipWithNext().any { (a, b) -> a.timeMs >= b.timeMs }) emptyList()
        else cached.candles
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { emptyList() }

    suspend fun write(series: Pair<String, Timeframe>, candles: List<Candle>) {
        store?.write(key(series), Json.encodeToString(CachedCandles(candles = candles)))
    }
}

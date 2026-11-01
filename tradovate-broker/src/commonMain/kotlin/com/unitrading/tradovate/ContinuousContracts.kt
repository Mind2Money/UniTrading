package com.unitrading.tradovate

import com.unitrading.broker.checkedAdd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.time.Instant

internal data class CompletedSession(
    val day: Long, val start: Long, val end: Long, val validUntil: Long,
    val verifyLatestDailyBar: Boolean = false,
)
internal data class VolumeLeader(val contract: JsonObject, val maturity: JsonObject, val session: CompletedSession, val volume: Long)

// Publication gaps are allowed only with fresh daily-history evidence, and never indefinitely.
private const val MAX_CALENDAR_TAIL_AGE_MS = 72 * 60 * 60 * 1000L
// A newer session cannot both start and complete between two checks this far apart.
internal const val CALENDAR_TAIL_REFRESH_MS = 15 * 60_000L
private const val MAX_CACHED_VOLUMES = 2_000
private const val CACHED_VOLUME_AGE_MS = 14 * 86_400_000L

@Serializable
private data class StoredEvidence(val version: Int = 1, val volumes: Map<String, Long> = emptyMap(), val leaders: Map<String, String> = emptyMap())

/**
 * A completed trade date's volume does not change, so re-verification reads it here instead of
 * opening one daily chart per month again. The last confirmed leader only seeds displayed quotes.
 * Not thread-safe: the broker reads and writes it under its state lock.
 */
internal class ContinuousEvidence(private val store: CandleCacheStore?, private val key: String) {
    private val volumes = mutableMapOf<String, Long>()
    private val leaders = mutableMapOf<String, String>()
    private var loaded = false

    /** Called before any route exists; a damaged or old cache only costs fresh queries. */
    suspend fun load(now: Long) {
        if (loaded) return
        loaded = true
        try {
            val stored = store?.read(key)?.let { Json.decodeFromString<StoredEvidence>(it) } ?: return
            if (stored.version != 1) return
            volumes.putAll(stored.volumes.filter { (entry, volume) ->
                val day = entry.substringAfterLast('/').toLongOrNull()
                volume >= 0 && day != null && runCatching { now - dailyBarTimestamp(day) in 0..CACHED_VOLUME_AGE_MS }.getOrDefault(false)
            })
            leaders.putAll(stored.leaders.filter { (symbol, contract) -> symbol.startsWith("@") && contract.isNotBlank() })
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { volumes.clear(); leaders.clear() }
    }

    fun volume(contract: String, day: Long): Long? = volumes["$contract/$day"]

    fun remember(contract: String, day: Long, volume: Long) {
        volumes["$contract/$day"] = volume
        if (volumes.size > MAX_CACHED_VOLUMES) volumes.keys.sortedBy { it.substringAfterLast('/') }
            .take(volumes.size - MAX_CACHED_VOLUMES).forEach(volumes::remove)
    }

    /** Drops one product's cached volumes for a trade date, e.g. after a completed-volume correction. */
    fun forget(root: String, day: Long) {
        val month = Regex(Regex.escape(root) + "[FGHJKMNQUVXZ][0-9]{1,4}")
        volumes.keys.removeAll { it.endsWith("/$day") && month.matches(it.substringBeforeLast('/')) }
    }

    fun leader(symbol: String): String? = leaders[symbol]
    fun lead(symbol: String, contract: String) { leaders[symbol] = contract }
    fun snapshot(): String = Json.encodeToString(StoredEvidence(volumes = volumes.toMap(), leaders = leaders.toMap()))

    suspend fun save(snapshot: String) {
        try { store?.write(key, snapshot) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Disposable cache failure must not interrupt resolution. */ }
    }
}

/** Exchange calendar, including overnight/holiday sessions; device calendar and isFront are irrelevant. */
internal fun completedSession(rows: List<JsonObject>, maturity: Long, now: Long): CompletedSession {
    data class Session(val day: Long, val start: Long, val end: Long)
    val sessions = rows.filter { it.long("contractMaturityId") == maturity }.map { row ->
        val date = requireNotNull(row["tradeDate"] as? JsonObject)
        val year = requireNotNull(date.long("year")); val month = requireNotNull(date.long("month")); val day = requireNotNull(date.long("day"))
        Instant.parse("$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}T00:00:00Z")
        val start = requireNotNull(row.str("startTime")?.let(::timestamp))
        val end = requireNotNull(row.str("endTime")?.let(::timestamp))
        // start/end delimit trading. Tradovate's open/close may surround that interval
        // (MES: open 21:30, start 22:00, end next day 21:00, close 21:29).
        require(start < end) { "交易时段无效" }
        Session(year * 10_000 + month * 100 + day, start, end)
    }
    require(sessions.map { it.day }.distinct().size == sessions.size) { "交易日时段不唯一" }
    require(sessions.sortedBy { it.end }.zipWithNext().all { (a, b) -> b.day > a.day && b.start >= a.end }) { "交易日时段重叠" }
    val previous = requireNotNull(sessions.filter { it.end <= now }.maxByOrNull { it.end }) { "缺少上一完整交易日" }
    val next = sessions.filter { it.end > now }.minByOrNull { it.end }
    if (next == null) {
        // Live calendars may end on Friday without publishing Monday yet. The last
        // completed session still supplies a trade date, but cannot supply a cache
        // deadline. The caller must check the latest history and refresh promptly.
        val deadline = checkedAdd(previous.end, MAX_CALENDAR_TAIL_AGE_MS)
        require(now < deadline) { "交易日历末尾已超过72小时，等待更新" }
        return CompletedSession(previous.day, previous.start, previous.end, deadline, verifyLatestDailyBar = true)
    }
    require(next.day > previous.day && next.start >= previous.end) { "交易日时段重叠" }
    return CompletedSession(previous.day, previous.start, previous.end, next.end)
}

/** Enumerate all listed, unexpired dated contracts. Incomplete candidates/data never become zero volume. */
internal suspend fun selectVolumeLeader(productId: Long, root: String, now: Long,
    read: suspend (String) -> JsonArray,
    volume: suspend (String, CompletedSession) -> Long,
    progress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
): VolumeLeader {
    val all = read("contractMaturity/deps?masterid=$productId").map { it.jsonObject }
    require(all.all { it.long("productId") == productId }) { "合约月份品种不匹配" }
    val maturities = all.filter { row -> requireNotNull(row.str("expirationDate")?.let(::timestamp)) { "缺少到期时间" } > now }
    require(maturities.isNotEmpty() && maturities.size <= 200) { "候选月份为空或超出完整查询上限" }
    val byId = maturities.associateBy { requireNotNull(it.long("id")) }
    require(byId.size == maturities.size) { "候选月份重复" }
    val contracts = byId.keys.chunked(50).flatMap { ids -> read("contract/ldeps?masterids=${ids.joinToString(",")}").map { it.jsonObject } }
    val pattern = Regex(Regex.escape(root) + "[FGHJKMNQUVXZ][0-9]{1,4}")
    require(contracts.isNotEmpty() && contracts.size <= 200 && contracts.all {
        it.long("id")?.let { id -> id > 0 } == true && it.long("contractMaturityId") in byId && it.str("name")?.matches(pattern) == true
    }) { "候选合约不完整或品种不匹配" }
    require(contracts.map { it.long("id") }.distinct().size == contracts.size &&
        contracts.map { it.long("contractMaturityId") }.distinct().size == contracts.size) { "候选合约不唯一" }
    val ids = contracts.map { it.long("contractMaturityId")!! }
    require(ids.toSet() == byId.keys) { "部分候选月份缺少合约，无法完整比较成交量" }
    val rows = ids.chunked(50).flatMap { chunk -> read("tradingSession/ldeps?masterids=${chunk.joinToString(",")}").map { it.jsonObject } }
    val sessions = ids.associateWith { completedSession(rows, it, now) }
    require(sessions.values.map { it.day }.distinct().size == 1) { "候选合约的完整交易日不一致" }
    val verifyLatest = sessions.values.any { it.verifyLatestDailyBar }
    val slots = Semaphore(3)
    val counted = Mutex()
    var done = 0
    val ranked = coroutineScope { contracts.map { contract -> async { slots.withPermit {
        val maturity = byId.getValue(contract.long("contractMaturityId")!!)
        val session = sessions.getValue(maturity.long("id")!!).copy(verifyLatestDailyBar = verifyLatest)
        val count = volume(contract.str("name")!!, session)
        require(count >= 0) { "日成交量无效" }
        counted.withLock { progress(++done, contracts.size) }
        VolumeLeader(contract, maturity, session, count)
    } } }.awaitAll() }
    val maximum = ranked.maxOf { it.volume }
    require(maximum > 0) { "上一完整交易日没有有效成交量" }
    val leader = requireNotNull(ranked.singleOrNull { it.volume == maximum }) { "上一完整交易日成交量并列，无法唯一确定主力" }
    val deadline = minOf(sessions.values.minOf { it.validUntil }, maturities.minOf { timestamp(it.str("expirationDate")!!)!! })
    return leader.copy(session = leader.session.copy(validUntil = deadline))
}

/** DailyBar timestamps label the trade date at UTC midnight, independently of session hours. */
internal fun dailyBarTimestamp(day: Long): Long {
    val text = day.toString()
    require(text.length == 8) { "日线交易日期无效" }
    return Instant.parse("${text.take(4)}-${text.substring(4, 6)}-${text.takeLast(2)}T00:00:00Z").toEpochMilliseconds()
}

internal fun dailyVolumeRange(session: CompletedSession) = buildJsonObject {
    val start = dailyBarTimestamp(session.day)
    put("asFarAsTimestamp", Instant.fromEpochMilliseconds(start).toString())
    // Without a future calendar row, a bounded query could hide a newer trade day.
    // Ask for the latest bar from this date onward, including overnight date labels.
    if (!session.verifyLatestDailyBar) put("closestTimestamp", Instant.fromEpochMilliseconds(start + 86_400_000 - 1).toString())
    put("asMuchAsElements", 1)
}

/** Only a successful RealTime subscription may treat an empty, explicitly ended history as zero. */
internal class DailyVolumeHistory(private val day: Long, private val allowEmpty: Boolean = false) {
    val result = CompletableDeferred<Long>()
    var invalidated = false
        private set
    private var confirmedVolume: Long? = null
    private val bars = mutableMapOf<Long, Long>()
    fun accept(packet: JsonObject) {
        if (invalidated) return
        try {
            val values = packet["bars"]
            require(values == null || values is JsonArray) { "日线数据格式无效" }
            for (value in (values as? JsonArray).orEmpty()) {
                val bar = requireNotNull(value as? JsonObject) { "日线数据格式无效" }
                val time = requireNotNull(bar.str("timestamp")?.let(::timestamp))
                val labels = listOf(packet, bar).filter { "td" in it }.map { requireNotNull(it.long("td")) { "日线交易日期不匹配或无效" } }
                require(labels.distinct().size <= 1) { "日线交易日期不匹配或无效" }
                // A labelled bar stamped at another date's UTC midnight contradicts itself.
                labels.singleOrNull()?.let { td -> require(time % 86_400_000 != 0L || time == dailyBarTimestamp(td)) { "日线交易日期不匹配或无效" } }
                // A month that did not trade on the date gets its latest older bar back
                // (live GCN7: 2026-10-28 for 2026-10-30), i.e. no volume on the date itself.
                if (labels.singleOrNull()?.let { it < day } ?: (time < dailyBarTimestamp(day))) continue
                // A later date means trading continued past the calendar (live: MBT weekend bars).
                val later = (labels.singleOrNull()?.takeIf { it > day }?.let(::dailyBarTimestamp)
                    ?: time.takeIf { labels.isEmpty() && it > dailyBarTimestamp(day) && (it - dailyBarTimestamp(day)) % 86_400_000 == 0L })
                    ?.let { Instant.fromEpochMilliseconds(it).toString().take(10) }
                require(later == null) { "交易日历尚未包含 $later 的日线，暂无法确认主力" }
                labels.singleOrNull()?.let { require(it == day) { "日线交易日期不匹配或无效" } }
                // Real DailyBar packets omit td; do not infer their date from the device
                // calendar or accept an arbitrary intraday timestamp as daily evidence.
                if (labels.isEmpty()) require(time == dailyBarTimestamp(day)) { "日线交易日期不匹配或缺失" }
                fun count(key: String): Long {
                    val value = requireNotNull(decimalUnits(bar.str(key), 6)) { "日成交量缺失" }
                    require(value >= 0 && value % 1_000_000 == 0L) { "日成交量无效" }
                    return value / 1_000_000
                }
                bars[time] = checkedAdd(count("upVolume"), count("downVolume"))
            }
            if (result.isCompleted) {
                // Packets can still arrive before the chart is cancelled. A new trade date or
                // correction invalidates the result instead of silently reusing it.
                if (bars.isNotEmpty()) require(bars.values.singleOrNull() == confirmedVolume) { "已完成日线发生更新" }
                return
            }
            if (packet.bool("eoh") == true) {
                confirmedVolume = if (bars.isEmpty() && allowEmpty) 0
                    else requireNotNull(bars.values.singleOrNull()) { "完整交易日日线缺失或不唯一" }
                result.complete(confirmedVolume!!)
            }
        } catch (e: Exception) { invalidated = true; result.completeExceptionally(e) }
    }
}

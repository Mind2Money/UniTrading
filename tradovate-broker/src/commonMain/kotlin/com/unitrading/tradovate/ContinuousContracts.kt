package com.unitrading.tradovate

import com.unitrading.broker.checkedAdd
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import kotlin.time.Instant

internal data class CompletedSession(val day: Long, val start: Long, val end: Long, val validUntil: Long)
internal data class VolumeLeader(val contract: JsonObject, val maturity: JsonObject, val session: CompletedSession, val volume: Long)

/** Exchange calendar, including overnight/holiday sessions; device calendar and isFront are irrelevant. */
internal fun completedSession(rows: List<JsonObject>, maturity: Long, now: Long): CompletedSession {
    data class Session(val day: Long, val start: Long, val end: Long)
    val sessions = rows.filter { it.long("contractMaturityId") == maturity }.map { row ->
        val date = requireNotNull(row["tradeDate"] as? JsonObject)
        val year = requireNotNull(date.long("year")); val month = requireNotNull(date.long("month")); val day = requireNotNull(date.long("day"))
        Instant.parse("$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}T00:00:00Z")
        val start = requireNotNull(row.str("startTime")?.let(::timestamp))
        val open = requireNotNull(row.str("openTime")?.let(::timestamp))
        val close = requireNotNull(row.str("closeTime")?.let(::timestamp))
        val end = requireNotNull(row.str("endTime")?.let(::timestamp))
        require(start <= open && open < close && close <= end) { "交易时段无效" }
        Session(year * 10_000 + month * 100 + day, start, end)
    }
    require(sessions.map { it.day }.distinct().size == sessions.size) { "交易日时段不唯一" }
    val previous = requireNotNull(sessions.filter { it.end <= now }.maxByOrNull { it.end }) { "缺少上一完整交易日" }
    val next = requireNotNull(sessions.filter { it.end > now }.minByOrNull { it.end }) { "交易日历未覆盖当前或后续交易日" }
    require(next.day > previous.day && next.start >= previous.end) { "交易日时段重叠" }
    return CompletedSession(previous.day, previous.start, previous.end, next.end)
}

/** Enumerate all listed, unexpired dated contracts. Incomplete candidates/data never become zero volume. */
internal suspend fun selectVolumeLeader(productId: Long, root: String, now: Long,
    read: suspend (String) -> JsonArray,
    volume: suspend (String, CompletedSession) -> Long,
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
    val ranked = contracts.map { contract ->
        val maturity = byId.getValue(contract.long("contractMaturityId")!!)
        val session = sessions.getValue(maturity.long("id")!!)
        val count = volume(contract.str("name")!!, session)
        require(count >= 0) { "日成交量无效" }
        VolumeLeader(contract, maturity, session, count)
    }
    val maximum = ranked.maxOf { it.volume }
    require(maximum > 0) { "上一完整交易日没有有效成交量" }
    val leader = requireNotNull(ranked.singleOrNull { it.volume == maximum }) { "上一完整交易日成交量并列，无法唯一确定主力" }
    val deadline = minOf(sessions.values.minOf { it.validUntil }, maturities.minOf { timestamp(it.str("expirationDate")!!)!! })
    return leader.copy(session = leader.session.copy(validUntil = deadline))
}

/** A one-day DailyBar response, isolated from visible charts. Wait for EOH and require server trade-date evidence. */
internal class DailyVolumeHistory(private val day: Long) {
    val result = CompletableDeferred<Long>()
    private val bars = mutableMapOf<Long, Long>()
    fun accept(packet: JsonObject) {
        if (result.isCompleted) return
        try {
            for (bar in packet.array("bars")) {
                require((bar.long("td") ?: packet.long("td")) == day) { "日线交易日期不匹配或缺失" }
                val time = requireNotNull(bar.str("timestamp")?.let(::timestamp))
                fun count(key: String): Long {
                    val value = requireNotNull(decimalUnits(bar.str(key), 6)) { "日成交量缺失" }
                    require(value >= 0 && value % 1_000_000 == 0L) { "日成交量无效" }
                    return value / 1_000_000
                }
                bars[time] = checkedAdd(count("upVolume"), count("downVolume"))
            }
            if (packet.bool("eoh") == true) result.complete(requireNotNull(bars.values.singleOrNull()) { "完整交易日日线缺失或不唯一" })
        } catch (e: Exception) { result.completeExceptionally(e) }
    }
}

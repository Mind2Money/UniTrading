package com.unitrading.chart

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import com.unitrading.broker.multiplyDivide

/** Generic local rules, not a representation of a particular firm's evaluation contract. */
internal enum class ReplayDrawdown { STATIC, EOD, TDD }
internal enum class ReplayDailyLoss { SOFT, HARD, NONE }

internal data class ReplayRiskConfig(val initialMicros: Long, val maxDrawdownMicros: Long, val targetMicros: Long,
    val mode: ReplayDrawdown, val allowOvernight: Boolean = true, val overnightCutoffMinutes: Int = 15 * 60 + 59,
    val maxDailyLossMicros: Long? = null, val dailyLossMode: ReplayDailyLoss = ReplayDailyLoss.SOFT,
    val consistencyPercent: Int = 0) {
    init {
        require(initialMicros > 0 && maxDrawdownMicros > 0 && targetMicros > 0) { "资金、回撤额度和盈利目标必须大于零" }
        require(initialMicros <= Long.MAX_VALUE - targetMicros) { "初始资金与盈利目标合计超出范围" }
        require(initialMicros > maxDrawdownMicros) { "最大回撤额度必须小于初始资金" }
        require(maxDailyLossMicros == null || maxDailyLossMicros > 0) { "最大日亏损必须大于零" }
        require(consistencyPercent in listOf(0, 20, 30, 40, 50)) { "一致性比例无效" }
        require(consistencyPercent == 0 || consistencyLimitMicros!! > 0) { "盈利目标过小，无法计算一致性额度" }
        require(overnightCutoffMinutes in 0 until 24 * 60) { "禁止过夜的平仓时间无效" }
    }
    val consistencyLimitMicros: Long? get() = if (consistencyPercent == 0) null else multiplyDivide(targetMicros, consistencyPercent.toLong(), 1L, 100L)
}

internal fun defaultReplayRiskConfig() = ReplayRiskConfig(
    50_000_000_000L, 2_000_000_000L, 3_000_000_000L, ReplayDrawdown.EOD,
    maxDailyLossMicros = 1_000_000_000L, dailyLossMode = ReplayDailyLoss.SOFT,
)

internal data class ReplayRiskState(val threshold: Long, val highWater: Long, val tradingDay: String? = null,
    val lastBalance: Long, val failed: Boolean = false, val targetReached: Boolean = false,
    val dayOpeningBalance: Long = lastBalance, val dailyLossHit: Boolean = false, val dailyFailure: Boolean = false,
    val lastEquity: Long = lastBalance, val consistencyHit: Boolean = false) {
    companion object {
        fun initial(config: ReplayRiskConfig) = ReplayRiskState(config.initialMicros - config.maxDrawdownMicros,
            config.initialMicros, lastBalance = config.initialMicros)
    }

    /** EOD rolls at 17:00 Chicago and uses the preceding balance. */
    fun observe(config: ReplayRiskConfig, timeMs: Long, balance: Long, equity: Long): ReplayRiskState {
        val local = Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(TimeZone.of("America/Chicago"))
        val day = (if (local.hour >= 17) local.date.plus(1, DateTimeUnit.DAY) else local.date).toString()
        val high = when (config.mode) {
            ReplayDrawdown.STATIC -> config.initialMicros
            ReplayDrawdown.TDD -> maxOf(highWater, equity)
            ReplayDrawdown.EOD -> if (tradingDay != null && tradingDay != day) maxOf(highWater, lastBalance) else highWater
        }
        val limit = maxOf(threshold, high - config.maxDrawdownMicros)
        val newDay = tradingDay != null && tradingDay != day
        val opening = if (newDay) lastEquity else dayOpeningBalance
        val hit = config.dailyLossMode != ReplayDailyLoss.NONE &&
            ((!newDay && dailyLossHit) || (config.maxDailyLossMicros?.let { equity <= opening - it } == true))
        val hard = hit && config.dailyLossMode == ReplayDailyLoss.HARD
        return copy(threshold = limit, highWater = high, tradingDay = day, lastBalance = balance,
            dayOpeningBalance = opening, dailyLossHit = hit, dailyFailure = dailyFailure || hard, lastEquity = equity,
            failed = failed || equity <= limit || hard,
            consistencyHit = !newDay && consistencyHit)
    }
}

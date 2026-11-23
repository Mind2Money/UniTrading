package com.unitrading.chart

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Generic local rules, not a representation of a particular firm's evaluation contract. */
internal enum class ReplayDrawdown { STATIC, EOD, TDD }

internal data class ReplayRiskConfig(val initialMicros: Long, val maxDrawdownMicros: Long, val targetMicros: Long,
    val mode: ReplayDrawdown) {
    init {
        require(initialMicros > 0 && maxDrawdownMicros > 0 && targetMicros > 0) { "资金、回撤额度和盈利目标必须大于零" }
        require(initialMicros > maxDrawdownMicros) { "最大回撤额度必须小于初始资金" }
    }
}

internal data class ReplayRiskState(val threshold: Long, val highWater: Long, val tradingDay: String? = null,
    val lastBalance: Long, val failed: Boolean = false, val targetReached: Boolean = false) {
    companion object {
        fun initial(config: ReplayRiskConfig) = ReplayRiskState(config.initialMicros - config.maxDrawdownMicros,
            config.initialMicros, lastBalance = config.initialMicros)
    }

    /** Called at observed candle closes. EOD rolls at 17:00 Chicago and uses the preceding balance. */
    fun observe(config: ReplayRiskConfig, timeMs: Long, balance: Long, equity: Long): ReplayRiskState {
        val local = Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(TimeZone.of("America/Chicago"))
        val day = (if (local.hour >= 17) local.date.plus(1, DateTimeUnit.DAY) else local.date).toString()
        val high = when (config.mode) {
            ReplayDrawdown.STATIC -> config.initialMicros
            ReplayDrawdown.TDD -> maxOf(highWater, equity)
            ReplayDrawdown.EOD -> if (tradingDay != null && tradingDay != day) maxOf(highWater, lastBalance) else highWater
        }
        val limit = maxOf(threshold, high - config.maxDrawdownMicros)
        return copy(threshold = limit, highWater = high, tradingDay = day, lastBalance = balance,
            failed = failed || equity <= limit,
            targetReached = targetReached || balance - config.initialMicros >= config.targetMicros)
    }
}

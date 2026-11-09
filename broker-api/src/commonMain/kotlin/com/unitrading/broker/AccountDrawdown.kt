package com.unitrading.broker

import kotlinx.serialization.Serializable

/** How the broker moves its line: TDD trails during the day, EOD at the close, a fixed line stays where it is. */
@Serializable
enum class DrawdownMode { REAL_TIME, END_OF_DAY, STEPPED_REAL_TIME, STEPPED_END_OF_DAY, FIXED, OTHER }

/** Which figure a line the broker reports is. [LAST_CLOSE] is computed by the app from what the broker sends. */
@Serializable
enum class DrawdownLineSource { CURRENT_VALUE, THRESHOLD, MIN_BALANCE, LAST_CLOSE }

/** What the account value under the room is: cash plus marked positions follow the market, a snapshot lags it. */
@Serializable
enum class DrawdownValueSource { QUOTES, BROKER, CASH, SNAPSHOT }

/**
 * A broker's drawdown rule. [levelMicros] is the line, null when the broker names a rule but no level; [amountMicros] is
 * one full trailing amount, null for a line with nothing to measure it against. [reported] marks a broker that reports
 * its line itself; the figures after it are that broker's own words, kept to compare with its platform. Whatever the
 * broker did not send stays null, never zero.
 */
@Serializable
data class DrawdownRule(
    val levelMicros: Long? = null,
    val amountMicros: Long? = null,
    val peakMicros: Long? = null,
    val limitMicros: Long? = null,
    val held: Boolean = false,
    val mode: DrawdownMode? = null,
    val reported: Boolean = false,
    val lineSource: DrawdownLineSource? = null,
    val criteria: String? = null,
    val thresholdMicros: Long? = null,
    val balanceCapMicros: Long? = null,
    val lossLimitMicros: Long? = null,
    val minBalanceMicros: Long? = null,
    val lossPercentMicros: Long? = null,
)

/** The account's value at this moment and where it came from. */
data class DrawdownAccountValue(val micros: Long, val source: DrawdownValueSource)

/**
 * One account's room above its drawdown line. [danger] is the share of one full trailing amount already used, 0 to 1 on
 * one scale for TDD and EOD: 1 at or below the line, 0 with a full amount of room or more. Display data only; no trading
 * decision reads it.
 */
@Serializable
data class AccountDrawdown(
    val rule: DrawdownRule,
    val roomMicros: Long? = null,
    val danger: Double? = null,
    val source: DrawdownValueSource? = null,
)

/**
 * The room and danger of [rule] at [value]: null for an account without a rule. Without a value or a line the rule is kept
 * and the room stays unknown; without a trailing amount the room shows and danger stays unknown.
 */
fun accountDrawdown(rule: DrawdownRule?, value: DrawdownAccountValue?): AccountDrawdown? {
    if (rule == null) return null
    val level = rule.levelMicros
    if (value == null || level == null) return AccountDrawdown(rule)
    val room = runCatching { checkedAdd(value.micros, -level) }.getOrNull() ?: return AccountDrawdown(rule)
    val danger = rule.amountMicros?.takeIf { it > 0 }?.let { (1.0 - room.toDouble() / it).coerceIn(0.0, 1.0) }
    return AccountDrawdown(rule, room, danger, value.source)
}

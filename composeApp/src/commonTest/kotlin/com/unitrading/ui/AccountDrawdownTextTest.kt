package com.unitrading.ui

import com.unitrading.broker.*
import kotlin.test.*

class AccountDrawdownTextTest {
    private fun usd(value: Long) = value * 1_000_000
    private val tdd = AccountDrawdown(DrawdownRule(146_078_500_000, usd(4_500), 150_578_500_000, mode = DrawdownMode.REAL_TIME), usd(1_125), 0.75, DrawdownValueSource.QUOTES)

    @Test fun anAccountWithoutARuleStaysADashAndSaysWhy() {
        assertEquals("—", drawdownRoom(null))
        assertNull(drawdownRuleText(null))
        assertEquals("—", drawdownDangerLabel(null, true))
        assertEquals("未取得券商的回撤规则", drawdownDetail(null))
    }

    @Test fun theRoomAndTheDangerNameTheirRuleAndSource() {
        assertEquals("1125.00", drawdownRoom(tdd))
        assertEquals("TDD · 线 146078.50", drawdownRuleText(tdd))
        assertEquals("75% · 行情实时", drawdownDangerLabel(tdd, true))
        assertEquals("75% · 行情中断", drawdownDangerLabel(tdd, false), "a lost link never reads as live")
        assertEquals("爆号线 146078.50：最高净值 150578.50 − 追踪回撤 4500.00", drawdownDetail(tdd))
    }

    @Test fun anEndOfDayLineSaysWhenItMoves() {
        val eod = AccountDrawdown(DrawdownRule(usd(47_500), usd(2_500), usd(50_000), mode = DrawdownMode.END_OF_DAY), usd(4_000), 0.0, DrawdownValueSource.SNAPSHOT)
        assertEquals("EOD · 线 47500.00", drawdownRuleText(eod))
        assertEquals("0% · 券商快照", drawdownDangerLabel(eod, true))
        assertEquals("0% · 缓存快照", drawdownDangerLabel(eod, false))
        assertEquals("爆号线 47500.00：最高净值 50000.00 − 追踪回撤 2500.00。EOD 的线在收盘后更新", drawdownDetail(eod))
    }

    @Test fun aHeldLineIsNamedAndACrossedLineShowsANegativeRoom() {
        val held = AccountDrawdown(DrawdownRule(usd(150_000), usd(4_500), usd(156_000), usd(150_000), held = true, mode = DrawdownMode.STEPPED_REAL_TIME), -46_500_000, 1.0, DrawdownValueSource.CASH)
        assertEquals("-46.50", drawdownRoom(held))
        assertEquals("阶梯 TDD 已锁定 · 线 150000.00", drawdownRuleText(held))
        assertEquals("100% · 空仓 · 现金余额", drawdownDangerLabel(held, true))
        assertEquals("爆号线 150000.00：已锁定在上限（最高净值 156000.00，追踪回撤 4500.00）", drawdownDetail(held))
    }

    @Test fun aLineKnownBeforeTheValueLeavesTheRoomUnknownNotZero() {
        val waiting = AccountDrawdown(DrawdownRule(usd(47_500), usd(2_500), usd(50_000)))
        assertEquals("—", drawdownRoom(waiting))
        assertEquals("追踪回撤 · 线 47500.00", drawdownRuleText(waiting))
        assertEquals("—", drawdownDangerLabel(waiting, true))
    }

    @Test fun theBarTurnsAtHalfAndAtFourFifths() {
        assertEquals(listOf(DangerTone.SAFE, DangerTone.CAUTION, DangerTone.CAUTION, DangerTone.ALARM, DangerTone.ALARM), listOf(0.49, 0.5, 0.79, 0.8, 1.0).map(::dangerTone))
        assertEquals("0%", dangerPercent(0.0))
        assertEquals("100%", dangerPercent(1.0))
    }

    @Test fun aLineTheBrokerReportsNamesItsFiguresAndAMissingAmount() {
        val trailing = AccountDrawdown(DrawdownRule(usd(48_500), usd(2_000), usd(50_500), mode = DrawdownMode.REAL_TIME, reported = true, lineSource = DrawdownLineSource.CURRENT_VALUE), usd(900), 0.55, DrawdownValueSource.BROKER)
        assertEquals("55% · 券商实时", drawdownDangerLabel(trailing, true))
        assertEquals("55% · 缓存快照", drawdownDangerLabel(trailing, false))
        assertEquals("爆号线 48500.00：券商当前值，峰值余额 50500.00，回撤额度 2000.00", drawdownDetail(trailing))
        val held = AccountDrawdown(DrawdownRule(usd(50_100), usd(2_000), usd(53_000), usd(50_100), held = true, mode = DrawdownMode.REAL_TIME, reported = true, lineSource = DrawdownLineSource.CURRENT_VALUE), usd(300), 0.85, DrawdownValueSource.CASH)
        assertEquals("TDD 已锁定 · 线 50100.00", drawdownRuleText(held))
        assertTrue(drawdownDetail(held).endsWith("。已锁定在上限"))
        val fixed = AccountDrawdown(DrawdownRule(usd(47_500), mode = DrawdownMode.FIXED, reported = true, lineSource = DrawdownLineSource.THRESHOLD), usd(500), null, DrawdownValueSource.CASH)
        assertEquals("固定 · 线 47500.00", drawdownRuleText(fixed))
        assertEquals("—", drawdownDangerLabel(fixed, true))
        assertEquals("爆号线 47500.00：券商当前值。券商没有给出回撤额度，无法计算危险度", drawdownDetail(fixed))
    }

    @Test fun aRuleWithoutALineShowsTheBrokersOwnFigures() {
        val unreported = AccountDrawdown(DrawdownRule(null, usd(1_500), limitMicros = usd(25_000), mode = DrawdownMode.END_OF_DAY, reported = true, criteria = "Multiple Simultaneous Criteria",
            thresholdMicros = usd(1_500), balanceCapMicros = usd(25_000), lossLimitMicros = usd(90_000)))
        assertEquals("—", drawdownRoom(unreported))
        assertEquals("EOD · 券商未上报爆号线", drawdownRuleText(unreported))
        assertEquals("—", drawdownDangerLabel(unreported, true))
        assertEquals("券商规则 Multiple Simultaneous Criteria：阈值 1500.00，上限余额 25000.00，亏损上限 90000.00。券商没有上报当前爆号线，可回撤额度和危险度未知", drawdownDetail(unreported))
        val other = AccountDrawdown(DrawdownRule(mode = DrawdownMode.OTHER, reported = true, criteria = "Percentage of Cash on Hand Lost", minBalanceMicros = usd(23_500), lossPercentMicros = 12_500_000))
        assertEquals("其他规则 · 券商未上报爆号线", drawdownRuleText(other))
        assertEquals("券商规则 Percentage of Cash on Hand Lost：最低余额 23500.00，最大允许亏损 12.5%。券商没有上报当前爆号线，可回撤额度和危险度未知", drawdownDetail(other))
    }

    @Test fun aLineFromTheMinimumBalanceOrTheLastCloseSaysSo() {
        val minimum = AccountDrawdown(DrawdownRule(usd(23_500), usd(1_500), limitMicros = usd(25_000), mode = DrawdownMode.END_OF_DAY, reported = true, lineSource = DrawdownLineSource.MIN_BALANCE), usd(1_523), 0.0, DrawdownValueSource.CASH)
        assertEquals("爆号线 23500.00：券商最低余额，回撤额度 1500.00。EOD 的线在收盘后更新", drawdownDetail(minimum))
        val closing = AccountDrawdown(DrawdownRule(23_523_000_000, usd(1_500), 25_023_000_000, usd(25_000), mode = DrawdownMode.END_OF_DAY, reported = true, lineSource = DrawdownLineSource.LAST_CLOSE), usd(1_500), 0.0, DrawdownValueSource.CASH)
        assertEquals("爆号线 23523.00：上次收盘余额 25023.00，回撤额度 1500.00。这条线由程序计算，亏损日后额度可能偏大", drawdownDetail(closing))
    }
}

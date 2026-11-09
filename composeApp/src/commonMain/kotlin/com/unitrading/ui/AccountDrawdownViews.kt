package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unitrading.broker.*
import kotlin.math.round

private const val UNKNOWN = "—"

/** The rule as prop firms name it: TDD trails during the day, EOD at the close. */
private fun drawdownKind(rule: DrawdownRule): String {
    val kind = when (rule.mode) {
        DrawdownMode.REAL_TIME -> "TDD"
        DrawdownMode.END_OF_DAY -> "EOD"
        DrawdownMode.STEPPED_REAL_TIME -> "阶梯 TDD"
        DrawdownMode.STEPPED_END_OF_DAY -> "阶梯 EOD"
        DrawdownMode.FIXED -> "固定"
        DrawdownMode.OTHER -> "其他规则"
        null -> "追踪回撤"
    }
    return if (rule.held) "$kind 已锁定" else kind
}

/** The room above the broker's line; a room nobody can know stays a dash, never zero. */
internal fun drawdownRoom(drawdown: AccountDrawdown?): String = drawdown?.roomMicros?.let { money(it) } ?: UNKNOWN

/** The rule and the line itself; null for an account without a known rule. */
internal fun drawdownRuleText(drawdown: AccountDrawdown?): String? = drawdown?.rule?.let { rule ->
    drawdownKind(rule) + (rule.levelMicros?.let { " · 线 ${money(it)}" } ?: " · 券商未上报爆号线")
}

/** Where the room's account value came from: quotes and cash follow the market, a snapshot lags it. A lost link never reads as live. */
internal fun drawdownSource(drawdown: AccountDrawdown?, connected: Boolean): String = when (drawdown?.source) {
    DrawdownValueSource.QUOTES -> if (connected) "行情实时" else "行情中断"
    DrawdownValueSource.BROKER -> if (connected) "券商实时" else "缓存快照"
    DrawdownValueSource.CASH -> if (connected) "空仓 · 现金余额" else "缓存快照"
    DrawdownValueSource.SNAPSHOT -> if (connected) "券商快照" else "缓存快照"
    null -> ""
}

internal fun dangerPercent(danger: Double): String = "${round(danger * 100).toInt()}%"

internal fun drawdownDangerLabel(drawdown: AccountDrawdown?, connected: Boolean): String =
    drawdown?.danger?.let { "${dangerPercent(it)} · ${drawdownSource(drawdown, connected)}" } ?: UNKNOWN

internal enum class DangerTone { SAFE, CAUTION, ALARM }

/** The bar's color: it turns at half of the trailing amount used and again at four fifths. */
internal fun dangerTone(danger: Double): DangerTone = when { danger >= 0.8 -> DangerTone.ALARM; danger >= 0.5 -> DangerTone.CAUTION; else -> DangerTone.SAFE }

/** A broker rule with no reported line, in the broker's own words and figures, to compare with its platform. */
private fun ruleWithoutLine(rule: DrawdownRule): String {
    fun figure(label: String, value: Long?) = value?.let { "$label ${money(it)}" }
    val figures = listOfNotNull(figure("阈值", rule.thresholdMicros), figure("上限余额", rule.balanceCapMicros), figure("亏损上限", rule.lossLimitMicros), figure("最低余额", rule.minBalanceMicros),
        rule.lossPercentMicros?.let { "最大允许亏损 ${formatFixed(it, 6).trimEnd('0').trimEnd('.')}%" })
    return "券商规则 ${rule.criteria.orEmpty()}" + (if (figures.isEmpty()) "" else "：" + figures.joinToString("，")) + "。券商没有上报当前爆号线，可回撤额度和危险度未知"
}

/** How the line comes about, in the words of the broker's figures. */
internal fun drawdownDetail(drawdown: AccountDrawdown?): String {
    val rule = drawdown?.rule ?: return "未取得券商的回撤规则"
    val line = rule.levelMicros?.let { money(it) }
    val peak = rule.peakMicros?.let { money(it) }
    val amount = rule.amountMicros?.let { money(it) }
    val endOfDay = rule.mode == DrawdownMode.END_OF_DAY || rule.mode == DrawdownMode.STEPPED_END_OF_DAY
    if (rule.reported) {
        if (line == null) return ruleWithoutLine(rule)
        val fromClose = rule.lineSource == DrawdownLineSource.LAST_CLOSE
        val origin = when (rule.lineSource) { DrawdownLineSource.LAST_CLOSE -> null; DrawdownLineSource.MIN_BALANCE -> "券商最低余额"; else -> "券商当前值" }
        val figures = listOfNotNull(origin, peak?.let { if (fromClose) "上次收盘余额 $it" else "峰值余额 $it" }, amount?.let { "回撤额度 $it" })
        val detail = "爆号线 $line：" + figures.joinToString("，")
        return when {
            rule.held -> "$detail。已锁定在上限"
            amount == null -> "$detail。券商没有给出回撤额度，无法计算危险度"
            fromClose -> "$detail。这条线由程序计算，亏损日后额度可能偏大"
            endOfDay -> "$detail。EOD 的线在收盘后更新"
            else -> detail
        }
    }
    val origin = if (rule.held) "爆号线 $line：已锁定在上限（最高净值 $peak，追踪回撤 $amount）" else "爆号线 $line：最高净值 $peak − 追踪回撤 $amount"
    return if (endOfDay) "$origin。EOD 的线在收盘后更新" else origin
}

@Composable
private fun dangerColor(danger: Double): Color = when (dangerTone(danger)) {
    DangerTone.SAFE -> TradingColors.Profit
    DangerTone.CAUTION -> TradingColors.Caution
    DangerTone.ALARM -> TradingColors.Loss
}

/** The danger meter: how much of one full trailing amount is used. */
@Composable
private fun DangerValue(danger: Double?, modifier: Modifier) {
    val style = MaterialTheme.typography.titleSmall
    if (danger == null) { Text(UNKNOWN, style = style, maxLines = 1); return }
    val color = dangerColor(danger)
    val bar = RoundedCornerShape(4.dp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(8.dp).clip(bar).background(MaterialTheme.colorScheme.outlineVariant)) {
            if (danger > 0) Box(Modifier.fillMaxWidth(danger.toFloat()).fillMaxHeight().clip(bar).background(color))
        }
        Text(dangerPercent(danger), style = style, color = color, maxLines = 1)
    }
}

/** Screen readers get the percentage and its source as one figure. */
private fun Modifier.dangerSemantics(drawdown: AccountDrawdown?, connected: Boolean) =
    testTag("drawdown-danger").semantics(mergeDescendants = true) { contentDescription = "危险度 ${drawdownDangerLabel(drawdown, connected)}" }

/** Desktop status bar: the room and the meter on one line, how the line comes about on hover. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrawdownStrip(drawdown: AccountDrawdown?, connected: Boolean) {
    val type = MaterialTheme.typography
    // Not focusable: a focusable tooltip shown by hover would swallow a click elsewhere.
    TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(), { PlainTooltip { Text(drawdownDetail(drawdown)) } }, rememberTooltipState(), focusable = false) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Metric(drawdownRuleText(drawdown)?.let { "可回撤额度（$it）" } ?: "可回撤额度", drawdownRoom(drawdown), color = TradingColors.pnl(drawdown?.roomMicros), valueTag = "drawdown-room-value")
            Column(Modifier.dangerSemantics(drawdown, connected), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val source = drawdownSource(drawdown, connected).takeIf { drawdown?.danger != null && it.isNotEmpty() }
                Text(source?.let { "危险度（$it）" } ?: "危险度", style = type.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                DangerValue(drawdown?.danger, Modifier.width(120.dp))
            }
        }
    }
}

/** Phone account card: no hover here, so how the line comes about is spelled out under the figures. */
@Composable
internal fun DrawdownSummary(drawdown: AccountDrawdown?, connected: Boolean) {
    val type = MaterialTheme.typography
    val muted = TradingColors.Muted
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Metric("可回撤额度", drawdownRoom(drawdown), color = TradingColors.pnl(drawdown?.roomMicros), valueTag = "drawdown-room-value")
                drawdownRuleText(drawdown)?.let { Text(it, Modifier.testTag("drawdown-rule"), color = muted, style = type.labelSmall) }
            }
            Column(Modifier.weight(1f).dangerSemantics(drawdown, connected), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("危险度", style = type.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                DangerValue(drawdown?.danger, Modifier.fillMaxWidth())
                if (drawdown?.danger != null) Text(drawdownSource(drawdown, connected), color = muted, style = type.labelSmall, maxLines = 1)
            }
        }
        Text(drawdownDetail(drawdown), Modifier.testTag("drawdown-detail"), color = muted, style = type.bodySmall)
    }
}

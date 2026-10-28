package com.unitrading.demo

import com.unitrading.broker.AccountInfo
import com.unitrading.broker.Instrument
import com.unitrading.broker.MICROS_PER_USD
import kotlinx.serialization.Serializable

/** USD linear contracts only. Changing any setting makes an old snapshot incompatible. */
@Serializable
data class DemoConfig(
    val id: String,
    val name: String,
    val description: String,
    val instruments: List<Instrument>,
    val accounts: List<AccountInfo>,
    val initialBids: Map<String, Long>,
    val spreadTicks: Map<String, Long>,
    val volatilityTicks: Map<String, Int>,
    val randomSeed: Int,
    val startingBalanceMicros: Long = 25_000 * MICROS_PER_USD,
    val startTimeMs: Long = 1_798_502_400_000L,
    val staleAfterMs: Long = 60_000L,
)

object DemoConfigs {
    val all: List<DemoConfig> = listOf(
        DemoConfig(
            id = "atlas-demo",
            name = "Atlas Demo",
            description = "模拟外汇与黄金 · USD · 每手单边手续费 $3.50",
            instruments = listOf(
                Instrument("EURUSD", "欧元 / 美元", "外汇", 5, tickValueMicrosPerLot = 1_000_000,
                    minStopTicks = 20, commissionMicrosPerLot = 3_500_000),
                Instrument("GBPUSD", "英镑 / 美元", "外汇", 5, tickValueMicrosPerLot = 1_000_000,
                    minStopTicks = 25, commissionMicrosPerLot = 3_500_000),
                Instrument("XAUUSD", "黄金 / 美元", "贵金属", 2, tickValueMicrosPerLot = 1_000_000,
                    leverage = 50, minStopTicks = 50, commissionMicrosPerLot = 3_500_000),
            ),
            accounts = listOf(AccountInfo("atlas-01", "Atlas · 主账户"), AccountInfo("atlas-02", "Atlas · 策略账户")),
            initialBids = mapOf("EURUSD" to 108_500L, "GBPUSD" to 128_700L, "XAUUSD" to 265_000L),
            spreadTicks = mapOf("EURUSD" to 12L, "GBPUSD" to 16L, "XAUUSD" to 30L),
            volatilityTicks = mapOf("EURUSD" to 5, "GBPUSD" to 7, "XAUUSD" to 20),
            randomSeed = 0x13572468,
        ),
        DemoConfig(
            id = "meridian-demo",
            name = "Meridian Demo",
            description = "模拟数字资产与黄金 · USD · 独立合约规格与费率",
            instruments = listOf(
                Instrument("BTCUSD", "比特币 / 美元", "数字资产", 2, tickSize = 5,
                    tickValueMicrosPerLot = 50_000, maxLotsHundredths = 1_000,
                    leverage = 5, minStopTicks = 200, commissionMicrosPerLot = 25_000_000),
                Instrument("XAUUSD", "黄金 / 美元", "贵金属", 2, tickSize = 5,
                    tickValueMicrosPerLot = 5_000_000, maxLotsHundredths = 2_000,
                    leverage = 30, minStopTicks = 12, commissionMicrosPerLot = 5_000_000),
            ),
            accounts = listOf(AccountInfo("meridian-01", "Meridian · 主账户"), AccountInfo("meridian-02", "Meridian · 策略账户")),
            initialBids = mapOf("BTCUSD" to 6_750_000L, "XAUUSD" to 265_000L),
            spreadTicks = mapOf("BTCUSD" to 120L, "XAUUSD" to 8L),
            volatilityTicks = mapOf("BTCUSD" to 80, "XAUUSD" to 5),
            randomSeed = 0x24681357,
        ),
    )
}

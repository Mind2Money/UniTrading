package com.unitrading.app

import com.unitrading.broker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.random.Random
import kotlin.test.*

private class SoundBroker(id: String = "test") : BrokerPlugin {
    override val descriptor = BrokerDescriptor(id, id, "Sound test")
    override val capabilities = BrokerCapabilities()
    override val instruments = emptyList<Instrument>()
    override val accounts = emptyList<AccountInfo>()
    override val catalog = MutableStateFlow(BrokerCatalog(instruments, accounts))
    override val market = MutableStateFlow(MarketSnapshot())
    override val tradeFills = MutableSharedFlow<TradeFill>(extraBufferCapacity = 32)
    override fun openSession(accountId: String): AccountSession = error("Sound monitoring must not open an account session")
    override fun history(symbol: String, timeframe: Timeframe) = emptyList<Candle>()
}

@OptIn(ExperimentalCoroutinesApi::class)
class TradeSoundsTest {
    private fun fill(order: String = "1", account: String = "A", role: OrderRole = OrderRole.TAKE_PROFIT, execution: String = "fill-$order") =
        TradeFill(account, order, execution, role)

    @Test fun selectsOneProfitClipAndRandomLossClips() {
        val random = Random(87)
        repeat(30) { assertEquals(TradeSounds.takeProfit, TradeSounds.select(OrderRole.TAKE_PROFIT, random)) }
        val loss = List(100) { TradeSounds.select(OrderRole.STOP_LOSS, random) }
        assertTrue(loss.all { it in TradeSounds.stopLoss })
        assertEquals(12, loss.toSet().size)
        assertEquals(TradeSounds.entry, TradeSounds.select(OrderRole.ENTRY, random))
        assertFailsWith<IllegalStateException> { TradeSounds.select(OrderRole.UNRESOLVED, random) }
    }

    @Test fun duplicatesAndPartialFillsPlayOnceButAccountsRemainIndependent() = runTest {
        val broker = SoundBroker()
        val played = mutableListOf<String>()
        val player = object : TradeSoundPlayer { override suspend fun play(resourcePath: String) { played.add(resourcePath) } }
        backgroundScope.launch { TradeSoundController(player, Random(1)).run(BrokerRegistry(listOf(broker))) }
        runCurrent()
        broker.tradeFills.emit(fill())
        broker.tradeFills.emit(fill(execution = "partial-2"))
        broker.tradeFills.emit(fill(account = "B", role = OrderRole.STOP_LOSS))
        runCurrent()
        assertEquals(2, played.size)
        assertEquals(TradeSounds.takeProfit, played.first())
        assertTrue(played.last() in TradeSounds.stopLoss)
    }

    @Test fun playbackIsSequentialAndFailureDoesNotStopLaterSounds() = runTest {
        val broker = SoundBroker()
        val starts = mutableListOf<Long>()
        var active = 0
        val player = object : TradeSoundPlayer {
            override suspend fun play(resourcePath: String) {
                assertEquals(0, active++)
                starts.add(testScheduler.currentTime)
                try { delay(100); if (starts.size == 1) error("No audio device") }
                finally { active-- }
            }
        }
        val task = backgroundScope.launch { TradeSoundController(player).run(BrokerRegistry(listOf(broker))) }
        runCurrent()
        repeat(3) { broker.tradeFills.emit(fill(order = "$it")) }
        runCurrent(); advanceTimeBy(301); runCurrent()
        assertEquals(listOf(0L, 100L, 200L), starts)
        task.cancelAndJoin()
        assertEquals(0, active)
    }

    @Test fun replacingPluginDropsOldQueuedEventsAndDoesNotDuplicateOtherCollectors() = runTest {
        val a = SoundBroker("a")
        val b = SoundBroker("b")
        val registry = BrokerRegistry(listOf(a, b))
        val played = mutableListOf<String>()
        val player = object : TradeSoundPlayer {
            override suspend fun play(resourcePath: String) { played.add(resourcePath); delay(100) }
        }
        backgroundScope.launch { TradeSoundController(player).run(registry) }
        runCurrent()
        a.tradeFills.emit(fill("a1")); a.tradeFills.emit(fill("a2")); runCurrent()
        val replacement = SoundBroker("a")
        registry.register(replacement); runCurrent()
        a.tradeFills.emit(fill("a3"))
        replacement.tradeFills.emit(fill("new", role = OrderRole.STOP_LOSS))
        b.tradeFills.emit(fill("b1"))
        advanceTimeBy(400); runCurrent()
        assertEquals(3, played.size)
        assertTrue(played[1] in TradeSounds.stopLoss)
        assertEquals(0, a.tradeFills.subscriptionCount.value)
        assertEquals(1, b.tradeFills.subscriptionCount.value)
    }

    @Test fun cancellationReleasesAnActivePlaybackAndCollectors() = runTest {
        val broker = SoundBroker()
        var released = false
        val player = object : TradeSoundPlayer {
            override suspend fun play(resourcePath: String) { try { awaitCancellation() } finally { released = true } }
        }
        val task = backgroundScope.launch { TradeSoundController(player).run(BrokerRegistry(listOf(broker))) }
        runCurrent(); broker.tradeFills.emit(fill()); runCurrent()
        task.cancelAndJoin()
        assertTrue(released)
        assertEquals(0, broker.tradeFills.subscriptionCount.value)
    }

    @Test fun hungNativePlaybackTimesOutWithoutBlockingTheQueue() = runTest {
        val broker = SoundBroker()
        var calls = 0
        var released = 0
        val player = object : TradeSoundPlayer {
            override suspend fun play(resourcePath: String) {
                calls++
                try { if (calls == 1) awaitCancellation() } finally { released++ }
            }
        }
        backgroundScope.launch { TradeSoundController(player).run(BrokerRegistry(listOf(broker))) }
        runCurrent()
        broker.tradeFills.emit(fill("one")); broker.tradeFills.emit(fill("two"))
        runCurrent(); advanceTimeBy(10_001); runCurrent()
        assertEquals(2, calls)
        assertEquals(2, released)
    }

    @Test fun fillsAreSilentWhileTheSoundsAreOffAndAreNotPlayedOnceTheyAreBackOn() = runTest {
        val broker = SoundBroker()
        val enabled = MutableStateFlow(false)
        val played = mutableListOf<String>()
        val player = object : TradeSoundPlayer { override suspend fun play(resourcePath: String) { played.add(resourcePath) } }
        backgroundScope.launch { TradeSoundController(player, Random(1), enabled).run(BrokerRegistry(listOf(broker))) }
        runCurrent()
        broker.tradeFills.emit(fill("off", role = OrderRole.ENTRY)); runCurrent()
        assertEquals(emptyList(), played)
        enabled.value = true; runCurrent()
        assertEquals(emptyList(), played, "a fill that arrived while off is not played afterwards")
        broker.tradeFills.emit(fill("off", role = OrderRole.ENTRY, execution = "partial-2"))
        broker.tradeFills.emit(fill("on")); runCurrent()
        assertEquals(listOf(TradeSounds.takeProfit), played, "the order that filled while off stays silent, later orders sound")
    }

    @Test fun switchingTheSoundsOffStopsTheClipThatIsPlayingAndDropsTheOnesWaiting() = runTest {
        val broker = SoundBroker()
        val enabled = MutableStateFlow(true)
        val started = mutableListOf<String>()
        var released = 0
        val player = object : TradeSoundPlayer {
            override suspend fun play(resourcePath: String) { started.add(resourcePath); try { delay(1_000) } finally { released++ } }
        }
        backgroundScope.launch { TradeSoundController(player, Random(1), enabled).run(BrokerRegistry(listOf(broker))) }
        runCurrent()
        broker.tradeFills.emit(fill("playing", role = OrderRole.ENTRY)); broker.tradeFills.emit(fill("waiting"))
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(listOf(TradeSounds.entry), started)
        enabled.value = false; runCurrent()
        assertEquals(1, released, "the native player is released as soon as the switch goes off")
        advanceTimeBy(5_000); runCurrent()
        enabled.value = true; runCurrent()
        assertEquals(listOf(TradeSounds.entry), started, "the clip that was waiting is dropped, not played later")
        broker.tradeFills.emit(fill("after")); runCurrent()
        assertEquals(listOf(TradeSounds.entry, TradeSounds.takeProfit), started)
    }

    @Test fun eventsBeforeSubscriptionAreNotReplayed() = runTest {
        val broker = SoundBroker()
        broker.tradeFills.emit(fill())
        var calls = 0
        val player = object : TradeSoundPlayer { override suspend fun play(resourcePath: String) { calls++ } }
        backgroundScope.launch { TradeSoundController(player).run(BrokerRegistry(listOf(broker))) }
        runCurrent()
        assertEquals(0, calls)
    }
}

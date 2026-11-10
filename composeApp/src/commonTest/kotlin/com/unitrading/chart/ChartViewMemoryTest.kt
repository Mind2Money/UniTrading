package com.unitrading.chart

import com.unitrading.app.MemorySnapshotStore
import com.unitrading.broker.Candle
import com.unitrading.broker.Timeframe
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

class ChartViewMemoryTest {
    private fun bars(count: Int, from: Int = 0) = (from until from + count).map { Candle(it * 60_000L, 100, 110, 90, 105) }
    private fun memory(store: MemorySnapshotStore) = ChartViewMemory(store, Dispatchers.Unconfined, saveDelayMs = 0)

    @Test fun aViewOnTheNewestBarKeepsFollowingIt() {
        val view = SavedChartView.of(ChartViewport(40.0, 0.0), bars(100), null)
        assertEquals(SavedChartView(40.0), view)
        assertEquals(ChartViewport(40.0, 0.0), view.viewport(bars(130)), "new bars since: still on the newest one")
        val margin = SavedChartView.of(ChartViewport(40.0, -6.0), bars(100), null)
        assertEquals(ChartViewport(40.0, -6.0), margin.viewport(bars(130)), "space after the newest bar stays")
    }

    @Test fun aViewOfThePastShowsTheSameBarsHoweverManyArrivedSince() {
        val before = ChartViewport(40.0, 10.5)
        val view = SavedChartView.of(before, bars(100), null)
        assertEquals(89 * 60_000L, view.edgeTimeMs, "the bar cut by the right edge")
        assertEquals(.5, view.edgeCut)
        val after = view.viewport(bars(105))
        assertEquals(before.start(100), after.start(105), "the same first bar on screen")
        assertEquals(40.0, after.count)
        // Older history loaded on the left moves nothing either.
        assertEquals(before.start(100) + 50, view.viewport(bars(150, from = -50)).start(150))
    }

    @Test fun aPriceAxisSetByHandComesBackAndAFittedOneStaysFitted() {
        val view = SavedChartView.of(ChartViewport(), bars(100), PriceScale(95.0, 120.0, 10f, 300f))
        assertEquals(95.0 to 120.0, view.priceScale()!!.let { it.low to it.high })
        assertNull(SavedChartView.of(ChartViewport(), bars(100), null).priceScale())
    }

    @Test fun viewsPersistNewestLastAndTheOldestGoPastTheLimit() {
        val store = MemorySnapshotStore()
        val first = memory(store)
        assertEquals(ChartViewport().count, first.count, "nothing saved yet: the default bar count")
        first.keep(chartViewKey("MESZ6", Timeframe.M5), SavedChartView(30.0, edgeTimeMs = 5_000, edgeCut = .25))
        first.keep(chartViewKey("MESZ6", Timeframe.ofSeconds(30)), SavedChartView(120.0, low = 5_000.0, high = 5_100.0))
        val reopened = memory(store)
        assertEquals(SavedChartView(30.0, edgeTimeMs = 5_000, edgeCut = .25), reopened.view("MESZ6|5"))
        assertEquals(SavedChartView(120.0, low = 5_000.0, high = 5_100.0), reopened.view("MESZ6|30s"))
        assertEquals(120.0, reopened.count, "the bar count saved last")
        assertNull(reopened.view("MNQZ6|5"))

        repeat(ChartViewMemory.MAX_VIEWS) { reopened.keep("S$it|5", SavedChartView(50.0)) }
        val full = memory(store)
        assertNull(full.view("MESZ6|5"), "the oldest series are forgotten")
        assertNotNull(full.view("S0|5"))
        // Looking at a series again makes it the newest.
        full.keep("S0|5", SavedChartView(60.0))
        full.keep("NEW|5", SavedChartView(60.0))
        val again = memory(store)
        assertNotNull(again.view("S0|5"))
        assertNull(again.view("S1|5"))
    }

    @Test fun anInvalidViewIsNeitherKeptNorRead() {
        val store = MemorySnapshotStore()
        val views = memory(store)
        for (invalid in listOf(SavedChartView(5.0), SavedChartView(40.0, Double.NaN), SavedChartView(40.0, low = 10.0, high = 5.0),
            SavedChartView(40.0, low = 10.0), SavedChartView(40.0, edgeTimeMs = 1, edgeCut = 2.0))) {
            views.keep("MESZ6|5", invalid)
            assertNull(views.view("MESZ6|5"), "$invalid")
        }
        store.write(ChartViewMemory.KEY, """{"views":[{"key":"A|5","view":{"count":40.0}},{"key":"B|5","view":{"count":4000.0}}],"count":-1}""")
        val read = memory(store)
        assertEquals(SavedChartView(40.0), read.view("A|5"))
        assertNull(read.view("B|5"))
        assertEquals(ChartViewport().count, read.count)
        store.write(ChartViewMemory.KEY, "{broken")
        assertNull(memory(store).view("A|5"))
    }
}

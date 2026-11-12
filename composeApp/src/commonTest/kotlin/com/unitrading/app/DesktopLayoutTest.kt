package com.unitrading.app

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DesktopLayoutTest {
    @Test fun panelSizesPersistAndResetWithoutTouchingChartOrAccountState() = runTest {
        val store = MemorySnapshotStore()
        store.write("chart-v1", "unchanged")
        val controller = DesktopLayoutController(store)
        val custom = DesktopLayoutPreferences(310f, 370f, 300f, orderFlowFraction = .65f)
        controller.update(custom)
        assertNull(store.read("desktop-layout-v1"), "dragging only updates memory")
        assertTrue(controller.save())
        assertEquals(custom, DesktopLayoutController(store).preferences.value)
        controller.reset()
        assertTrue(controller.save())
        assertEquals(DesktopLayoutPreferences(), DesktopLayoutController(store).preferences.value)
        assertEquals("unchanged", store.read("chart-v1"))
    }

    @Test fun detachedPanelsAndWindowBoundsPersistAndResetBringsEveryPanelHome() = runTest {
        val store = MemorySnapshotStore()
        store.write("desktop-layout-v1", """{"orderWidth":260.0}""")
        val controller = DesktopLayoutController(store)
        assertTrue(controller.preferences.value.detached.isEmpty(), "a layout saved before windows existed keeps every panel in the main window")
        controller.setDetached(WorkspacePanel.CHART, true)
        controller.setDetached(WorkspacePanel.ORDER, true)
        controller.setDetached(WorkspacePanel.CHART, true)
        val bounds = WindowBounds(1700f, -40f, 900f, 640f)
        controller.setWindowBounds("CHART", bounds)
        controller.setWindowBounds("chart-2", WindowBounds(10f, 10f, 500f, 400f))
        assertTrue(controller.save())
        val reopened = DesktopLayoutController(store)
        assertEquals(setOf(WorkspacePanel.CHART, WorkspacePanel.ORDER), reopened.preferences.value.detached)
        assertEquals(bounds, reopened.preferences.value.windows["CHART"])
        assertEquals(260f, reopened.preferences.value.orderWidth)
        reopened.setDetached(WorkspacePanel.ORDER, false)
        reopened.setWindowBounds("chart-2", null)
        assertEquals(setOf(WorkspacePanel.CHART), reopened.preferences.value.detached)
        assertEquals(setOf("CHART"), reopened.preferences.value.windows.keys, "a closed chart window's bounds are forgotten")
        reopened.reset()
        assertEquals(DesktopLayoutPreferences(), reopened.preferences.value)
    }

    @Test fun windowBoundsThatCannotBeAWindowAreNeitherKeptNorLoaded() = runTest {
        val controller = DesktopLayoutController(MemorySnapshotStore())
        for (bad in listOf(WindowBounds(0f, 0f, 50f, 400f), WindowBounds(0f, 0f, 400f, 50f), WindowBounds(Float.NaN, 0f, 400f, 400f),
            WindowBounds(0f, 30_000f, 400f, 400f), WindowBounds(0f, 0f, 20_000f, 400f))) {
            controller.setWindowBounds("CHART", bad)
            assertTrue(controller.preferences.value.windows.isEmpty(), "$bad")
            assertFailsWith<IllegalArgumentException> { controller.update(DesktopLayoutPreferences(windows = mapOf("CHART" to bad))) }
        }
        assertFailsWith<IllegalArgumentException> { controller.update(DesktopLayoutPreferences(windows = mapOf("" to WindowBounds(0f, 0f, 400f, 400f)))) }
        repeat(DesktopLayoutPreferences.MAX_WINDOWS + 5) { controller.setWindowBounds("chart-$it", WindowBounds(it.toFloat(), 0f, 400f, 400f)) }
        assertEquals(DesktopLayoutPreferences.MAX_WINDOWS, controller.preferences.value.windows.size, "the list of remembered windows is bounded")
        val damaged = MemorySnapshotStore().apply { write("desktop-layout-v1", """{"detached":["CHART"],"windows":{"CHART":{"x":0.0,"y":0.0,"width":1.0,"height":1.0}}}""") }
        val loaded = DesktopLayoutController(damaged)
        assertEquals(DesktopLayoutPreferences(), loaded.preferences.value)
        assertNotNull(loaded.error.value)
    }

    /** The accounts panel left the workspace on 2026-11-11; a layout that still names it opens with everything else kept. */
    @Test fun aLayoutWithTheRemovedAccountsPanelStillOpens() {
        val store = MemorySnapshotStore()
        store.write("desktop-layout-v1", """{"sidebarWidth":300.0,"accountsHeight":180.0,"detached":["ACCOUNTS","CHART"],""" +
            """"windows":{"CHART":{"x":10.0,"y":20.0,"width":900.0,"height":600.0}}}""")
        val controller = DesktopLayoutController(store)
        assertNull(controller.error.value)
        assertEquals(300f, controller.preferences.value.sidebarWidth)
        assertEquals(setOf(WorkspacePanel.CHART), controller.preferences.value.detached)
        assertEquals(WindowBounds(10f, 20f, 900f, 600f), controller.preferences.value.windows["CHART"])
    }

    @Test fun foldedPanelsPersistAndALayoutSavedBeforeThemOpensWithEverythingShown() = runTest {
        val store = MemorySnapshotStore()
        store.write("desktop-layout-v1", """{"orderWidth":260.0,"accountsHeight":196.125,"terminalHeight":179.875}""")
        val controller = DesktopLayoutController(store)
        assertNull(controller.error.value)
        assertEquals(DesktopLayoutPreferences(orderWidth = 260f, terminalHeight = 179.875f), controller.preferences.value, "the removed accounts height is dropped")
        controller.setOpen(DesktopPanel.MARKET, false)
        controller.setOpen(DesktopPanel.TERMINAL, false)
        assertTrue(controller.save())
        val reopened = DesktopLayoutController(store).preferences.value
        assertFalse(reopened.marketOpen)
        assertTrue(reopened.orderOpen)
        assertFalse(reopened.terminalOpen)
        assertEquals(260f, reopened.orderWidth)
        controller.reset()
        assertTrue(controller.preferences.value.marketOpen && controller.preferences.value.terminalOpen)
    }

    @Test fun corruptionFallsBackAndFailedSavesKeepTheCurrentLayoutForRetry() = runTest {
        var disk = "{bad"
        var fail = true
        val controller = DesktopLayoutController(object : SnapshotStore {
            override fun read(key: String) = disk
            override fun write(key: String, value: String) { if (fail) error("disk full") else disk = value }
        })
        assertNotNull(controller.error.value)
        assertEquals(DesktopLayoutPreferences(), controller.preferences.value)
        assertFailsWith<IllegalArgumentException> { controller.update(DesktopLayoutPreferences(sidebarWidth = Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { controller.update(DesktopLayoutPreferences(orderWidth = -1f)) }
        assertFailsWith<IllegalArgumentException> { controller.update(DesktopLayoutPreferences(orderFlowFraction = Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { controller.update(DesktopLayoutPreferences(orderFlowFraction = 1f)) }
        val custom = DesktopLayoutPreferences(sidebarWidth = 320f)
        controller.update(custom)
        assertFalse(controller.save())
        assertEquals(custom, controller.preferences.value)
        fail = false
        assertTrue(controller.save())
        assertNull(controller.error.value)
    }

    @Test fun rapidSavesEndWithTheLatestDimensionsAndLoadingIsLazy() = runTest {
        val reads = mutableListOf<String>()
        val delegate = MemorySnapshotStore()
        val runtime = TradingRuntime(object : SnapshotStore {
            override fun read(key: String): String? { reads += key; return delegate.read(key) }
            override fun write(key: String, value: String) = delegate.write(key, value)
        })
        // Besides the theme, only the analytics cache is read at start, in the background, to trim it.
        runtime.analyticsTrim.join()
        assertEquals(listOf("theme-v1", "analytics-trades-v1"), reads)
        val layout = runtime.desktopLayout
        layout.update(DesktopLayoutPreferences(sidebarWidth = 280f))
        val first = launch { layout.save() }
        val latest = DesktopLayoutPreferences(sidebarWidth = 340f, orderWidth = 400f)
        layout.update(latest)
        val last = launch { layout.save() }
        first.join(); last.join()
        assertEquals(latest, layout.preferences.value)
        assertEquals(latest, DesktopLayoutController(delegate).preferences.value)
    }
}

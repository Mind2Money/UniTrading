package com.unitrading.app

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DesktopLayoutTest {
    @Test fun panelSizesPersistAndResetWithoutTouchingChartOrAccountState() = runTest {
        val store = MemorySnapshotStore()
        store.write("chart-v1", "unchanged")
        val controller = DesktopLayoutController(store)
        val custom = DesktopLayoutPreferences(310f, 370f, 180f, 300f, orderFlowFraction = .65f)
        controller.update(custom)
        assertNull(store.read("desktop-layout-v1"), "dragging only updates memory")
        assertTrue(controller.save())
        assertEquals(custom, DesktopLayoutController(store).preferences.value)
        controller.reset()
        assertTrue(controller.save())
        assertEquals(DesktopLayoutPreferences(), DesktopLayoutController(store).preferences.value)
        assertEquals("unchanged", store.read("chart-v1"))
    }

    @Test fun foldedPanelsPersistAndALayoutSavedBeforeThemOpensWithEverythingShown() = runTest {
        val store = MemorySnapshotStore()
        store.write("desktop-layout-v1", """{"orderWidth":260.0,"accountsHeight":196.125,"terminalHeight":179.875}""")
        val controller = DesktopLayoutController(store)
        assertNull(controller.error.value)
        assertEquals(DesktopLayoutPreferences(orderWidth = 260f, accountsHeight = 196.125f, terminalHeight = 179.875f), controller.preferences.value)
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
        assertEquals(listOf("theme-v1"), reads)
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

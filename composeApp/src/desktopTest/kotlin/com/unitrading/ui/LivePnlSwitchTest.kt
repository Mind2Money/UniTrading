package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class LivePnlSwitchTest {
    @get:Rule val compose = createComposeRule()

    /** The controller runs on a background dispatcher here, so the recorder guards its lists. */
    private class ThreadSafePresenter : LivePnlPresenter {
        private val lock = Any()
        private val updates = mutableListOf<LivePnlUpdate>()
        private var clears = 0
        val shown: List<LivePnlUpdate> get() = synchronized(lock) { updates.toList() }
        val cleared: Int get() = synchronized(lock) { clears }
        override fun show(update: LivePnlUpdate) { synchronized(lock) { updates += update } }
        override fun clear() { synchronized(lock) { clears++ } }
    }

    @Test fun switchAppearsOnlyWithAPresenterAndTogglesTheNotice() {
        val store = MemorySnapshotStore()
        val runtime = TradingRuntime(store, includeDemoBrokers = true)
        runtime.paused.value = true
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.75f)) {
            Box(Modifier.requiredSize(400.dp, 740.dp).testTag("test-host")) { TradingScreen(runtime) }
        } }
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("open-theme").performScrollTo()
        compose.onNodeWithTag("live-pnl-switch").assertDoesNotExist()
        val presenter = ThreadSafePresenter()
        val job = CoroutineScope(Dispatchers.Default).launch { runtime.livePnl.run(presenter) }
        try {
            compose.waitUntil(10_000) { presenter.shown.isNotEmpty() }
            val demo = runtime.brokers.first()
            assertEquals("${demo.descriptor.name} · ${demo.accounts.first().name}", presenter.shown.first().title)
            assertEquals(demo.openSession(demo.accounts.first().id).state.value.floatingPnlMicros, presenter.shown.first().pnlMicros)
            compose.onNodeWithTag("live-pnl-switch").performScrollTo().assertIsOn().performClick()
            compose.waitUntil(10_000) { presenter.cleared == 1 }
            compose.onNodeWithTag("live-pnl-switch").assertIsOff()
            compose.waitUntil(10_000) { !LivePnlController(store).settings.value.enabled }
        } finally { runBlocking { job.cancelAndJoin() } }
        assertEquals(2, presenter.cleared)
        assertFalse(runtime.livePnl.available.value)
    }
}

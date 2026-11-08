package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/** Items of different heights: 40, 200, 80 and 60 tall with 10 between them, so tops are 0, 50, 260 and 350. */
class ReorderableColumnTest {
    @get:Rule val compose = createComposeRule()
    private val heights = linkedMapOf("short" to 40, "tall" to 200, "mid" to 80, "last" to 60)
    private val all = heights.keys.toList()
    private var items by mutableStateOf(all)
    private lateinit var area: ReorderViewport

    private fun render(viewport: Int = 500) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            TradingTheme {
                area = rememberReorderViewport()
                Box(Modifier.requiredSize(300.dp, viewport.dp)) {
                    Column(Modifier.fillMaxSize().then(area.scrolling)) {
                        ReorderableColumn(area, items, { it }, { it }, { items = it }, spacing = 10.dp) { item ->
                            Column(Modifier.fillMaxWidth().height(heights.getValue(item.value).dp).testTag("card-${item.value}")) {
                                Row(Modifier.fillMaxWidth().height(40.dp).testTag("grip-${item.value}").then(item.grip)) { ReorderGrip() }
                            }
                        }
                    }
                }
            }
        } }
    }
    private fun top(item: String) = compose.onNodeWithTag("card-$item").fetchSemanticsNode().boundsInRoot.top
    private fun press(item: String) = compose.onNodeWithTag("grip-$item").performMouseInput { moveTo(center); press() }
    private fun move(item: String, y: Float) = compose.onNodeWithTag("grip-$item").performMouseInput { moveBy(Offset(0f, y)) }
    private fun release(item: String) = compose.onNodeWithTag("grip-$item").performMouseInput { release() }

    @Test fun aTallItemMovingUpPassesAnItemOnceItsTopCrossesThatItemsMiddle() {
        render()
        press("tall"); move("tall", -29f)
        assertEquals(0f, top("short"), "Still below the middle of the short item")
        move("tall", -2f)
        assertEquals(210f, top("short"), "Moves down by the held height plus the gap")
        release("tall")
        compose.runOnIdle { assertEquals(listOf("tall", "short", "mid", "last"), items) }
        assertEquals(0f, top("tall"))
        assertEquals(210f, top("short"))
    }

    @Test fun aShortItemMovingDownPassesAnItemOnceItsBottomCrossesThatItemsMiddle() {
        render()
        press("short"); move("short", 109f)
        assertEquals(50f, top("tall"))
        move("short", 2f)
        assertEquals(0f, top("tall"), "Moves up by the held height plus the gap")
        release("short")
        compose.runOnIdle { assertEquals(listOf("tall", "short", "mid", "last"), items) }
        assertEquals(210f, top("short"))
    }

    @Test fun aTallItemDroppedBelowAnotherLandsWithoutOverlappingIt() {
        render()
        press("tall"); move("tall", 51f); release("tall")
        compose.runOnIdle { assertEquals(listOf("short", "mid", "tall", "last"), items) }
        assertEquals(50f, top("mid"))
        assertEquals(140f, top("tall"))
        assertEquals(350f, top("last"))
    }

    @Test fun aTallItemScrollsOnlyWhenThePointerReachesTheEdge() {
        render(viewport = 300) // The list is 410 tall.
        press("tall")
        move("tall", 100f)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, area.scroll.value, "Its far end is past the viewport but the pointer is not near the edge") }
        move("tall", 100f)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(110, area.scroll.value) }
        release("tall")
        compose.runOnIdle { assertEquals(listOf("short", "mid", "last", "tall"), items) }
    }

    @Test fun aTallItemFollowsThePointerPastTheViewportWhileAStripStaysVisible() {
        render(viewport = 300)
        press("tall")
        move("tall", 150f)
        assertEquals(200f, top("tall"), "Its bottom is hidden; it keeps following the pointer")
        release("tall")
    }
}

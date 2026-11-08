package com.unitrading

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.unitrading.broker.*
import com.unitrading.tradovate.*
import com.unitrading.ui.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in executable, never discovered by the test runner. Credentials stay in memory. */
@OptIn(ExperimentalTestApi::class)
fun main(args: Array<String>) = runBlocking {
    require(args.contentEquals(arrayOf("--saved-demo-read-only")))
    val wire = ReadOnlyProbeWire(allowCapacityChecks = true)
    var broker: TradovateBroker? = null
    var verified = false
    var phase = "saved-session"
    try {
        val saved = Json.parseToJsonElement(requireNotNull(DesktopCredentialStore().read())).jsonObject
        val preferred = requireNotNull(saved["brokerId"]?.jsonPrimitive?.content)
        val credentials = saved["sessions"]!!.jsonArray.map { Json.decodeFromJsonElement<TradovateSession>(it) }
            .first { it.environment == TradovateEnvironment.DEMO && "tradovate-demo-${it.userId}" == preferred }
        check(credentials.expiresAtMs!! > System.currentTimeMillis())
        phase = "orders-before"
        val before = wire.request(credentials.hosts, credentials.accessToken, "GET", "order/list")
        val connected = TradovateBroker(credentials, wire)
        broker = connected
        phase = "connect"
        withTimeout(90_000) { connected.connect() }
        phase = "selected-account"
        val preferredAccount = saved["accounts"]?.jsonObject?.get(preferred)?.jsonPrimitive?.content
        // Match TradingScreen when no per-broker account selection has been saved yet.
        val account = connected.accounts.firstOrNull { it.id == preferredAccount } ?: connected.accounts.first()
        phase = "instrument"
        val spec = connected.instruments.firstOrNull { it.symbol == "MESZ6" }
            ?: connected.instruments.first { !it.isContinuous }
        connected.watch(spec.symbol, Timeframe.M5)
        val session = connected.openSession(account.id)
        phase = "capacity"
        val capacity = withTimeout(90_000) {
            session.orderCapacity(OrderRequest(spec.symbol, Side.BUY, OrderKind.MARKET, spec.minLotsHundredths))
        }
        val maximum = requireNotNull(capacity.maximumLotsHundredths) { "Capacity unavailable" }
        check(maximum > spec.minLotsHundredths) { "This diagnostic requires at least two selectable quantities" }
        phase = "orders-after"
        val after = wire.request(credentials.hosts, credentials.accessToken, "GET", "order/list")
        check(before == after) { "Server orders changed during read-only verification" }
        connected.disconnect()
        broker = null
        phase = "slider-drag"
        // Exercise touch dragging with the actual server-derived range, without an order action.
        runDesktopComposeUiTest(width = 440, height = 220) {
            var draft by mutableStateOf(OrderDraft(quantity = spec.formatQuantity(spec.minLotsHundredths)))
            setContent {
                TradingTheme {
                    Box(Modifier.requiredSize(400.dp, 180.dp)) {
                        OrderQuantitySlider(spec, draft, { draft = it }, true, QuantityCapacityState(maximum), {})
                    }
                }
            }
            onNodeWithTag("order-quantity-slider").assertIsEnabled().performTouchInput {
                swipe(Offset(width * .1f, centerY), Offset(width * .8f, centerY))
            }
            runOnIdle {
                val quantity = requireNotNull(spec.parseQuantity(draft.quantity))
                assertTrue(quantity > spec.minLotsHundredths && quantity <= maximum)
                assertEquals(0L, quantity % spec.lotStepHundredths)
            }
        }
        check(wire.refusedWrites == 0)
        verified = true
        println("CAPACITY_READ_ONLY_RESULT " + buildJsonObject {
            put("verified", true)
            put("symbol", spec.symbol)
            put("maximumLotsHundredths", maximum)
            put("capacityChecks", wire.capacityChecks)
            put("implicitSuccessResponses", wire.implicitSuccessResponses)
            put("ordersUnchanged", before == after)
            put("sliderDragVerified", true)
            put("sentTradingWrites", 0)
            put("refusedWrites", wire.refusedWrites)
        })
    } catch (error: Exception) {
        println("CAPACITY_READ_ONLY_RESULT " + buildJsonObject {
            put("verified", false)
            put("errorType", error.javaClass.simpleName)
            put("phase", phase)
            if (error is TradovateRemoteException) put("httpStatus", error.status)
            put("capacityChecks", wire.capacityChecks)
            put("implicitSuccessResponses", wire.implicitSuccessResponses)
            put("sentTradingWrites", 0)
            put("refusedWrites", wire.refusedWrites)
        })
    } finally {
        broker?.disconnect()
        wire.close()
    }
    if (!verified) kotlin.system.exitProcess(2)
}

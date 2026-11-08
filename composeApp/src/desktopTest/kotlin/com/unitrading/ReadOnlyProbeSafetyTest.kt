package com.unitrading

import com.unitrading.tradovate.TradovateEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReadOnlyProbeSafetyTest {
    @Test fun dailyDiagnosticsRejectTradingWritesAndDryrun() = runBlocking {
        val wire = ReadOnlyProbeWire(captureDailyEvidence = true)
        val blocked = listOf("order/placeorder", "order/placeoso", "order/placeoco", "order/modifyorder",
            "order/cancelorder", "order/liquidateposition", "orderstrategy/startorderstrategy", "order/dryrun")
        try {
            for (path in blocked) assertFailsWith<IllegalStateException> {
                wire.request(TradovateEnvironment.DEMO.defaultHosts, "synthetic-token-never-sent", "POST", path, null)
            }
            assertEquals(blocked.size, wire.refusedWrites)
            assertEquals(0, wire.capacityChecks)
        } finally { wire.close() }
    }

    @Test fun enablingCapacityChecksNeverEnablesTradingWrites() = runBlocking {
        val wire = ReadOnlyProbeWire(allowCapacityChecks = true)
        val writes = listOf("order/placeorder", "order/placeoso", "order/placeoco", "order/modifyorder",
            "order/cancelorder", "order/liquidateposition", "orderstrategy/startorderstrategy")
        try {
            for (path in writes) assertFailsWith<IllegalStateException> {
                wire.request(TradovateEnvironment.DEMO.defaultHosts, "synthetic-token-never-sent", "POST", path, null)
            }
            assertEquals(writes.size, wire.refusedWrites)
            assertEquals(0, wire.capacityChecks)
        } finally { wire.close() }
    }
}

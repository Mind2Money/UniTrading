package com.unitrading.rithmic

import kotlinx.coroutines.*
import kotlin.time.Clock

/** Lists the system names a gateway serves (template 16/17). No login or credentials are involved. */
suspend fun rithmicSystemNames(gateway: String, wire: RithmicWire = KtorRithmicWire()): List<String> = coroutineScope {
    val target = validateRithmicGateway(gateway)
    val probe = RithmicPlant("probe", wire.socket(target), this, { Clock.System.now().toEpochMilliseconds() }, {}, {})
    probe.start()
    try {
        withTimeout(10_000) { probe.request(Rt.REQUEST_SYSTEM_INFO) {} }.flatMap { it.strings(Rf.SYSTEM_NAME) }.distinct()
    } finally {
        probe.close(logout = false)
        if (wire is KtorRithmicWire) wire.close()
    }
}

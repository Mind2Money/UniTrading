package com.unitrading.rithmic

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlin.test.*

/** A raw socket whose replies the test scripts by hand. */
private class ScriptedSocket : RithmicWireSocket {
    val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    val sent = mutableListOf<ProtoMessage>()
    var closed = false
    override suspend fun send(bytes: ByteArray) { sent += unframeMessages(bytes) }
    override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()
    override suspend fun close() { closed = true; inbound.close() }
    fun push(build: ProtoWriter.() -> Unit) { inbound.trySend(frameMessage(ProtoWriter().apply(build).toByteArray())) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RithmicPlantTest {
    private fun TestScope.plant(socket: ScriptedSocket, events: MutableList<ProtoMessage> = mutableListOf(), closed: MutableList<String> = mutableListOf()) =
        RithmicPlant("test", socket, backgroundScope, { FAKE_TIME }, { events += it }, { closed += it }).also { it.start() }

    @Test fun repliesAreCollectedByRequestIdUntilTheTerminalPart() = runTest {
        val socket = ScriptedSocket()
        val plant = plant(socket)
        val reply = async { plant.request(Rt.REQUEST_ACCOUNT_LIST) { string(Rf.FCM_ID, "F") } }
        runCurrent()
        val id = socket.sent.single().userMsg()!!
        assertEquals(Rt.REQUEST_ACCOUNT_LIST, socket.sent.single().templateId)
        assertEquals("F", socket.sent.single().str(Rf.FCM_ID))
        socket.push { int32(Rf.TEMPLATE_ID, Rt.RESPONSE_ACCOUNT_LIST); string(Rf.USER_MSG, "other"); string(Rf.RP_CODE, "0"); string(Rf.ACCOUNT_ID, "X") }
        socket.push { int32(Rf.TEMPLATE_ID, Rt.RESPONSE_ACCOUNT_LIST); string(Rf.USER_MSG, id); string(Rf.RQ_HANDLER_RP_CODE, "0"); string(Rf.ACCOUNT_ID, "A") }
        socket.push { int32(Rf.TEMPLATE_ID, Rt.RESPONSE_ACCOUNT_LIST); string(Rf.USER_MSG, id); string(Rf.RQ_HANDLER_RP_CODE, "0"); string(Rf.ACCOUNT_ID, "B") }
        runCurrent()
        assertFalse(reply.isCompleted)
        socket.push { int32(Rf.TEMPLATE_ID, Rt.RESPONSE_ACCOUNT_LIST); string(Rf.USER_MSG, id); string(Rf.RP_CODE, "0") }
        runCurrent()
        assertEquals(listOf("A", "B", null), reply.await().map { it.str(Rf.ACCOUNT_ID) })
    }

    @Test fun errorCodesRejectsAndNoDataAreDistinguished() = runTest { supervisorScope {
        val socket = ScriptedSocket()
        val plant = plant(socket)
        val failing = async { plant.request(Rt.REQUEST_NEW_ORDER) {} }
        val rejected = async { plant.request(Rt.REQUEST_SHOW_ORDERS) {} }
        val empty = async { plant.request(Rt.REQUEST_REFERENCE_DATA) {} }
        runCurrent()
        val ids = socket.sent.map { it.userMsg()!! }
        socket.push { int32(Rf.TEMPLATE_ID, Rt.RESPONSE_NEW_ORDER); string(Rf.USER_MSG, ids[0]); strings(Rf.RP_CODE, listOf("9", "RMS limit")) }
        socket.push { int32(Rf.TEMPLATE_ID, Rt.REJECT); string(Rf.USER_MSG, ids[1]); strings(Rf.RP_CODE, listOf("5", "not logged in")) }
        socket.push { int32(Rf.TEMPLATE_ID, Rt.RESPONSE_REFERENCE_DATA); string(Rf.USER_MSG, ids[2]); strings(Rf.RP_CODE, listOf("7", "no data")) }
        runCurrent()
        val failure = assertFailsWith<RithmicRemoteException> { failing.await() }
        assertEquals("9", failure.code); assertEquals("RMS limit", failure.message)
        val reject = assertFailsWith<RithmicRemoteException> { rejected.await() }
        assertEquals("5", reject.code); assertTrue(reject.message!!.contains("not logged in"))
        assertEquals(1, empty.await().size)
    } }

    @Test fun aSilentServerTurnsTheTimeoutIntoAnUnknownResult() = runTest { supervisorScope {
        val socket = ScriptedSocket()
        val plant = plant(socket)
        val pending = async { plant.request(Rt.REQUEST_NEW_ORDER, 5_000) {} }
        advanceTimeBy(5_001); runCurrent()
        val error = assertFailsWith<RithmicRemoteException> { pending.await() }
        assertEquals("timeout", error.code)
        assertTrue(plant.connected)
    } }

    @Test fun aRefusedLoginIsAnErrorEvenWithTheNoDataCode() = runTest { supervisorScope {
        val socket = ScriptedSocket()
        val plant = plant(socket)
        val login = async { plant.login(RithmicCredentials(RithmicEnvironment.TEST, FAKE_GATEWAY, "Rithmic Test", "u", "hunter2-secret"), RithmicEnums.INFRA_ORDER) }
        runCurrent()
        socket.push { int32(Rf.TEMPLATE_ID, Rt.RESPONSE_LOGIN); string(Rf.USER_MSG, socket.sent.single().userMsg()!!); strings(Rf.RP_CODE, listOf("7", "bad password hunter2-secret for user u")) }
        runCurrent()
        val error = assertFailsWith<RithmicRemoteException> { login.await() }
        assertEquals("7", error.code)
        assertTrue(error.message!!.contains("bad password"), error.message)
        assertFalse(error.message!!.contains("hunter2"), error.message)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, socket.sent.count { it.templateId == Rt.REQUEST_HEARTBEAT })
    } }

    @Test fun loginStartsHeartbeatsAtHalfTheServerIntervalAndLogoutStopsThem() = runTest {
        val socket = ScriptedSocket()
        val plant = plant(socket)
        val login = async { plant.login(RithmicCredentials(RithmicEnvironment.TEST, FAKE_GATEWAY, "Rithmic Test", "u", "p"), RithmicEnums.INFRA_TICKER, aggregatedQuotes = false) }
        runCurrent()
        val request = socket.sent.single()
        assertEquals(Rt.REQUEST_LOGIN, request.templateId)
        assertEquals(RithmicEnums.INFRA_TICKER, request.int(Rf.INFRA_TYPE))
        assertEquals(false, request.bool(Rf.AGGREGATED_QUOTES))
        socket.push { int32(Rf.TEMPLATE_ID, Rt.RESPONSE_LOGIN); string(Rf.USER_MSG, request.userMsg()!!); string(Rf.RP_CODE, "0"); double(Rf.HEARTBEAT_INTERVAL, 20.0) }
        runCurrent()
        login.await()
        assertEquals(20_000, plant.heartbeatIntervalMs)
        advanceTimeBy(10_001); runCurrent()
        assertEquals(1, socket.sent.count { it.templateId == Rt.REQUEST_HEARTBEAT })
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, socket.sent.count { it.templateId == Rt.REQUEST_HEARTBEAT })
        plant.close(logout = true)
        assertEquals(Rt.REQUEST_LOGOUT, socket.sent.last().templateId)
        assertTrue(socket.closed)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, socket.sent.count { it.templateId == Rt.REQUEST_HEARTBEAT })
    }

    @Test fun unsolicitedFramesAreEventsAndForcedLogoutEndsTheConnection() = runTest { supervisorScope {
        val socket = ScriptedSocket()
        val events = mutableListOf<ProtoMessage>()
        val closed = mutableListOf<String>()
        val plant = plant(socket, events, closed)
        val pending = async { plant.request(Rt.REQUEST_SHOW_ORDERS) {} }
        runCurrent()
        socket.push { int32(Rf.TEMPLATE_ID, Rt.BEST_BID_OFFER); string(Rf.SYMBOL, "ESZ6") }
        socket.push { int32(Rf.TEMPLATE_ID, Rt.RESPONSE_HEARTBEAT) }
        socket.push { int32(Rf.TEMPLATE_ID, Rt.FORCED_LOGOUT) }
        runCurrent()
        assertEquals(listOf(Rt.BEST_BID_OFFER, Rt.FORCED_LOGOUT), events.map { it.templateId })
        assertFalse(plant.connected)
        assertTrue(closed.single().contains("强制登出"))
        val error = assertFailsWith<RithmicRemoteException> { pending.await() }
        assertEquals("closed", error.code)
    } }

    @Test fun aDroppedConnectionFailsPendingRequestsAndReportsOnce() = runTest { supervisorScope {
        val socket = ScriptedSocket()
        val closed = mutableListOf<String>()
        val plant = plant(socket, closed = closed)
        val pending = async { plant.request(Rt.REQUEST_SHOW_ORDERS) {} }
        runCurrent()
        socket.inbound.close()
        runCurrent()
        assertFailsWith<RithmicRemoteException> { pending.await() }
        assertEquals(1, closed.size)
        plant.close(logout = false)
        assertEquals(1, closed.size)
    } }
}

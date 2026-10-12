package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.livekit.MeetRecovery
import com.zillit.desktop.feature.calls.data.livekit.MeetRejoin
import com.zillit.desktop.feature.calls.data.livekit.MeetRejoinState
import com.zillit.desktop.feature.calls.domain.EngineConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A meet's media recovery: `/me` decides, three attempts at 1 s / 3 s / 8 s, then Rejoin — never a loop. */
@OptIn(ExperimentalCoroutinesApi::class)
class MeetRejoinTest {

    private class Script(var answers: MutableList<MeetRecovery>) {
        val asked = mutableListOf<String>()
        val reconnects = mutableListOf<String>()
        var ended = 0
    }

    private fun TestScope.rejoin(vararg answers: MeetRecovery): Pair<MeetRejoin, Script> {
        val script = Script(answers.toMutableList())
        val rejoin = MeetRejoin(
            scope = backgroundScope,
            attempt = { meetId ->
                script.asked += meetId
                script.answers.removeFirstOrNull() ?: MeetRecovery.Failed
            },
            reconnect = { _, token -> script.reconnects += token },
            ended = { script.ended++ },
        )
        return rejoin to script
    }

    @Test
    fun `out of a running room reconnects with the fresh token`() = runTest {
        val (rejoin, script) = rejoin(MeetRecovery.Reconnect("wss://in.livekit.zillit.com", "fresh"))
        rejoin.onConnection("m1", EngineConnection.Disconnected)
        advanceTimeBy(999)
        runCurrent()
        assertTrue(script.asked.isEmpty(), "the first attempt waits a second")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("fresh"), script.reconnects)
        assertEquals(MeetRejoinState.Recovering(1, 3), rejoin.state.value)

        rejoin.onConnection("m1", EngineConnection.Connected)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(MeetRejoinState.Idle, rejoin.state.value)
        assertEquals(1, script.asked.size, "restored media stops the attempts")
    }

    @Test
    fun `while the SDK still holds our place, no token is minted`() = runTest {
        val (rejoin, script) = rejoin(MeetRecovery.SdkReconnecting, MeetRecovery.SdkReconnecting)
        rejoin.onConnection("m1", EngineConnection.Reconnecting)
        advanceTimeBy(4_001)
        runCurrent()
        assertEquals(2, script.asked.size)
        assertTrue(script.reconnects.isEmpty())
    }

    @Test
    fun `three attempts, then Rejoin, and never a fourth on its own`() = runTest {
        val (rejoin, script) = rejoin()
        rejoin.onConnection("m1", EngineConnection.Disconnected)
        advanceTimeBy(1_001)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(3, script.asked.size)
        assertEquals(MeetRejoinState.Offered, rejoin.state.value)

        // Another drop while the button is up starts nothing.
        rejoin.onConnection("m1", EngineConnection.Disconnected)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, script.asked.size, "the recovery looped")

        // One press is one attempt, at once.
        rejoin.rejoinNow()
        runCurrent()
        assertEquals(4, script.asked.size)
        assertEquals(MeetRejoinState.Offered, rejoin.state.value)
    }

    @Test
    fun `a room that is gone ends the call`() = runTest {
        val (rejoin, script) = rejoin(MeetRecovery.Ended)
        rejoin.onConnection("m1", EngineConnection.Disconnected)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, script.ended)
        assertEquals(MeetRejoinState.Idle, rejoin.state.value)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, script.asked.size)
    }

    @Test
    fun `a Reconnecting then a Disconnected is one outage`() = runTest {
        val (rejoin, script) = rejoin()
        rejoin.onConnection("m1", EngineConnection.Reconnecting)
        rejoin.onConnection("m1", EngineConnection.Disconnected)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, script.asked.size)
    }
}

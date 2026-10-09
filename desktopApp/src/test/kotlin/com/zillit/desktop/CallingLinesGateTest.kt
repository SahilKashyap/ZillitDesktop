package com.zillit.desktop

import com.zillit.desktop.core.config.CallEngines
import com.zillit.desktop.core.config.CallLineNumbering
import com.zillit.desktop.core.config.CallingLinesConfig
import com.zillit.desktop.core.config.LegacyCallingFlags
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/** Where the gate takes `calling_lines` from: a fetch, the last fetch, the saved copy, or nothing. */
class CallingLinesGateTest {

    private val agoraOnly = """{"lines":[{"engine":"agora","order":1}]}"""
    private val liveKitOnly = """{"lines":[{"engine":"livekit","order":1}]}"""

    /** What the next fetch answers; null is "no fetch has ever succeeded". */
    private var answer: Map<String, String>? = null
    private var saved = ""
    private var saves = 0

    private fun TestScope.gate(liveKitConfigured: Boolean = true) = CallingLinesGate(
        fetch = { _: Duration -> answer },
        isFresh = { false },
        liveKitConfigured = liveKitConfigured,
        loadLastGood = { saved },
        saveLastGood = { saved = it; saves++ },
        scope = backgroundScope,
    )

    @AfterTest
    fun resetNumbering() = CallLineNumbering.update(null)

    @Test
    fun `12 - never fetched and nothing saved - the older rules and the built-in names`() = runTest {
        val gate = gate()
        gate.refresh()
        // line_three_enabled_in unread, so LiveKit is off, as it always was before a fetch.
        assertEquals(listOf(CallEngines.AGORA, CallEngines.MEDIASOUP), gate.available("p1"))
        assertEquals(3, CallLineNumbering.numberOf(CallEngines.MEDIASOUP))
    }

    @Test
    fun `a fetched value is applied, numbered, and saved once`() = runTest {
        val gate = gate()
        answer = mapOf(CallingLinesConfig.KEY to agoraOnly)
        gate.refresh()
        gate.refresh()
        assertEquals(listOf(CallEngines.AGORA), gate.available("p1"))
        assertEquals(1, CallLineNumbering.numberOf(CallEngines.AGORA))
        assertEquals(agoraOnly, saved)
        assertEquals(1, saves)
    }

    @Test
    fun `10 - a changed value is picked up on the next refresh`() = runTest {
        val gate = gate()
        answer = mapOf(CallingLinesConfig.KEY to agoraOnly)
        gate.refresh()
        answer = mapOf(CallingLinesConfig.KEY to liveKitOnly)
        gate.refresh()
        assertEquals(listOf(CallEngines.LIVEKIT), gate.available("p1"))
        assertEquals(liveKitOnly, saved)
    }

    @Test
    fun `11 - offline at launch - the saved copy decides`() = runTest {
        saved = liveKitOnly
        val gate = gate()
        gate.refresh()
        assertEquals(listOf(CallEngines.LIVEKIT), gate.available("p1"))
        assertEquals(0, saves)
    }

    @Test
    fun `5 - a fetch without the key falls back to the older flags, and keeps the saved copy`() = runTest {
        saved = liveKitOnly
        answer = mapOf(LegacyCallingFlags.LINE_THREE_ENABLED_IN to """["p1"]""")
        val gate = gate()
        gate.refresh()
        assertEquals(
            listOf(CallEngines.LIVEKIT, CallEngines.AGORA, CallEngines.MEDIASOUP),
            gate.available("p1"),
        )
        assertEquals(listOf(CallEngines.AGORA, CallEngines.MEDIASOUP), gate.available("p2"))
        assertEquals(liveKitOnly, saved)
    }

    @Test
    fun `an invalid value is never saved over a good one`() = runTest {
        saved = agoraOnly
        answer = mapOf(CallingLinesConfig.KEY to "{not json")
        gate().refresh()
        assertEquals(agoraOnly, saved)
        assertEquals(0, saves)
    }

    @Test
    fun `without the LiveKit backend LiveKit is never offered`() = runTest {
        answer = mapOf(CallingLinesConfig.KEY to liveKitOnly)
        val gate = gate(liveKitConfigured = false)
        gate.refresh()
        assertEquals(emptyList(), gate.available("p1"))
        assertEquals(false, gate.isAvailable(CallEngines.LIVEKIT, "p1"))
    }
}

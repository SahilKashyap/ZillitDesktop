package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.livekit.LiveKitActiveCall
import com.zillit.desktop.feature.calls.data.livekit.LiveKitRingWatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveKitRingWatchTest {
    private val watch = LiveKitRingWatch()

    private fun listed(vararg inCall: String) = listOf(LiveKitActiveCall("c1", inCall.toList()))

    @Test
    fun `a ring the server still lists stays up`() {
        assertNull(watch.judge("c1", "me", listed("caller")))
        assertNull(watch.judge("c1", "me", listed("caller")))
    }

    @Test
    fun `a ring answered on another device is over here`() {
        assertEquals(LiveKitRingWatch.Verdict.AnsweredElsewhere, watch.judge("c1", "me", listed("caller", "me")))
    }

    @Test
    fun `a never-listed ring gets one broadcast's grace, then is stale`() {
        assertNull(watch.judge("c1", "me", emptyList()), "an invite can beat the first roster update")
        assertEquals(LiveKitRingWatch.Verdict.Stale, watch.judge("c1", "me", emptyList()))
    }

    @Test
    fun `a ring that was listed is stale the moment it is not`() {
        assertNull(watch.judge("c1", "me", listed("caller")))
        assertEquals(LiveKitRingWatch.Verdict.Stale, watch.judge("c1", "me", emptyList()))
    }

    @Test
    fun `reset forgets the last ring's history`() {
        watch.judge("c1", "me", listed("caller"))
        watch.reset()
        assertNull(watch.judge("c2", "me", emptyList()), "a new ring starts with its own grace")
    }
}

package com.zillit.desktop.core.sync

import com.zillit.desktop.core.common.ZillitError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityMonitorTest {

    @Test
    fun `a transport failure means offline and any answer means online`() = runTest {
        val monitor = ConnectivityMonitor(backgroundScope)
        assertTrue(monitor.online.value, "starts optimistic")

        monitor.report(ZillitError.NoConnection())
        assertFalse(monitor.online.value)

        // A refusal proves the server is there.
        monitor.report(ZillitError.Http(status = 403))
        assertTrue(monitor.online.value)

        monitor.report(ZillitError.Timeout())
        assertFalse(monitor.online.value)
        monitor.report(null)
        assertTrue(monitor.online.value)
    }

    @Test
    fun `the socket coming up brings us back, its going down does not take us away`() = runTest {
        val socket = MutableStateFlow(false)
        val monitor = ConnectivityMonitor(backgroundScope, socketConnected = socket)
        monitor.start()

        monitor.report(ZillitError.NoConnection())
        assertFalse(monitor.online.value)

        socket.value = true
        runCurrent()
        assertTrue(monitor.online.value)

        socket.value = false
        runCurrent()
        assertTrue(monitor.online.value, "a socket drop is not evidence of no network")
    }

    @Test
    fun `while offline the probe is asked on a timer until it answers`() = runTest {
        var reachable = false
        var probes = 0
        val monitor = ConnectivityMonitor(
            backgroundScope,
            probe = { probes++; reachable },
            probeIntervalMillis = 1_000,
        )
        monitor.report(ZillitError.NoConnection())

        advanceTimeBy(3_500)
        assertEquals(3, probes)
        assertFalse(monitor.online.value)

        reachable = true
        advanceTimeBy(1_000)
        assertTrue(monitor.online.value)

        val settled = probes
        advanceTimeBy(5_000)
        assertEquals(settled, probes, "probing stops once online")
    }
}

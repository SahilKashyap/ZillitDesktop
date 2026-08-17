package com.zillit.desktop.feature.sides

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.sides.domain.GeneratePlan
import com.zillit.desktop.feature.sides.domain.GeneratePoller
import com.zillit.desktop.feature.sides.domain.SidesRecord
import com.zillit.desktop.feature.sides.domain.SidesStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The generate-and-poll loop, holding the web's pinned behaviours: `ready`
 * and `error` both terminal, generate failures propagate, transient poll
 * failures are swallowed, and the default budget is exactly 90 ticks.
 */
class GeneratePollerTest {

    private fun record(status: String, id: String = "S1") = SidesRecord(
        id = id, title = "t", status = SidesStatus.fromWire(status), rawStatus = status,
        error = "", sceneNumbers = emptyList(), totalScenes = 0, scriptTitle = "",
        versionLabel = "", generatedByName = "", generatedById = "", downloadCount = 0,
        createdAt = "", attachmentName = "",
    )

    private val plan = GeneratePlan(scriptId = "x", versionId = "v", sceneNumbers = listOf("1"))

    private fun poller(
        generate: suspend (GeneratePlan) -> ZillitResult<SidesRecord>,
        get: suspend (String) -> ZillitResult<SidesRecord>,
        maxTicks: Int = GeneratePoller.MAX_TICKS,
    ) = GeneratePoller(generate = generate, get = get, maxTicks = maxTicks, sleep = { })

    @Test
    fun `polls until ready and ticks every state`() = runTest {
        val states = ArrayDeque(listOf("generating", "generating", "ready"))
        val ticks = mutableListOf<String>()
        var gets = 0

        val outcome = poller(
            generate = { ZillitResult.Success(record("generating")) },
            get = {
                gets++
                ZillitResult.Success(record(states.removeFirst()))
            },
        ).run(plan) { ticks += it.rawStatus }

        val result = (outcome as ZillitResult.Success).data
        assertEquals(listOf("generating", "generating", "generating", "ready"), ticks)
        assertEquals(3, gets)
        assertEquals(SidesStatus.Ready, result.sides.status)
        assertFalse(result.timedOut)
    }

    @Test
    fun `terminal on the initial generate returns without polling`() = runTest {
        var gets = 0
        val outcome = poller(
            generate = { ZillitResult.Success(record("ready")) },
            get = { gets++; ZillitResult.Success(record("ready")) },
        ).run(plan)

        assertEquals(0, gets)
        assertFalse((outcome as ZillitResult.Success).data.timedOut)
    }

    @Test
    fun `error is terminal and does not throw`() = runTest {
        val outcome = poller(
            generate = { ZillitResult.Success(record("generating")) },
            get = { ZillitResult.Success(record("error")) },
        ).run(plan)

        val result = (outcome as ZillitResult.Success).data
        assertEquals(SidesStatus.Error, result.sides.status)
        assertFalse(result.timedOut)
    }

    @Test
    fun `a generate failure propagates`() = runTest {
        val outcome = poller(
            generate = { ZillitResult.Failure(ZillitError.Unknown("boom")) },
            get = { ZillitResult.Success(record("ready")) },
        ).run(plan)

        assertTrue(outcome is ZillitResult.Failure)
    }

    @Test
    fun `a transient poll failure is swallowed and the loop continues`() = runTest {
        var gets = 0
        val outcome = poller(
            generate = { ZillitResult.Success(record("generating")) },
            get = {
                gets++
                if (gets == 1) {
                    ZillitResult.Failure(ZillitError.Timeout())
                } else {
                    ZillitResult.Success(record("ready"))
                }
            },
        ).run(plan)

        assertEquals(2, gets)
        assertEquals(
            SidesStatus.Ready,
            (outcome as ZillitResult.Success).data.sides.status,
        )
    }

    @Test
    fun `exhausting the budget times out with the last-seen record`() = runTest {
        var gets = 0
        val outcome = poller(
            generate = { ZillitResult.Success(record("generating")) },
            get = { gets++; ZillitResult.Success(record("generating")) },
            maxTicks = 5,
        ).run(plan)

        val result = (outcome as ZillitResult.Success).data
        assertEquals(5, gets)
        assertTrue(result.timedOut)
        assertEquals(SidesStatus.Generating, result.sides.status)
    }

    @Test
    fun `default budget is exactly ninety ticks`() = runTest {
        var gets = 0
        val outcome = poller(
            generate = { ZillitResult.Success(record("generating")) },
            get = { gets++; ZillitResult.Success(record("generating")) },
        ).run(plan)

        assertEquals(90, gets)
        assertTrue((outcome as ZillitResult.Success).data.timedOut)
    }
}

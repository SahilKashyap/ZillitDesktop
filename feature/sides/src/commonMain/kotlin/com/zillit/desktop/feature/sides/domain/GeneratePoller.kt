package com.zillit.desktop.feature.sides.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.delay

/**
 * The generate-and-poll loop, a transcription of the web's pinned core
 * (`lib/generateAndPoll.js` + its test):
 *
 *  - the initial generate failure PROPAGATES;
 *  - `ready` and `error` are both terminal — an errored run returns, it
 *    never throws;
 *  - a transient poll failure is swallowed and the loop keeps going;
 *  - the budget is 90 ticks of 2 s; exhausting it returns the last-seen
 *    record with [PollOutcome.timedOut] set.
 *
 * Cancellation rides coroutine cancellation instead of an AbortSignal —
 * `delay` throws `CancellationException` exactly where the web's abortable
 * sleep rejects.
 */
class GeneratePoller(
    private val generate: suspend (GeneratePlan) -> ZillitResult<SidesRecord>,
    private val get: suspend (String) -> ZillitResult<SidesRecord>,
    private val intervalMs: Long = INTERVAL_MS,
    private val maxTicks: Int = MAX_TICKS,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    data class PollOutcome(val sides: SidesRecord, val timedOut: Boolean)

    suspend fun run(
        plan: GeneratePlan,
        onTick: (SidesRecord) -> Unit = {},
    ): ZillitResult<PollOutcome> {
        val initial = when (val started = generate(plan)) {
            is ZillitResult.Failure -> return started
            is ZillitResult.Success -> started.data
        }
        onTick(initial)
        if (initial.status.terminal) {
            return ZillitResult.Success(PollOutcome(initial, timedOut = false))
        }

        var last = initial
        repeat(maxTicks) {
            sleep(intervalMs)
            when (val polled = get(initial.id)) {
                // Transient poll failures are swallowed — the run is still
                // rendering server-side, and one dropped packet must not
                // abandon it.
                is ZillitResult.Failure -> Unit
                is ZillitResult.Success -> {
                    last = polled.data
                    onTick(last)
                    if (last.status.terminal) {
                        return ZillitResult.Success(PollOutcome(last, timedOut = false))
                    }
                }
            }
        }
        return ZillitResult.Success(PollOutcome(last, timedOut = true))
    }

    companion object {
        const val INTERVAL_MS = 2_000L
        const val MAX_TICKS = 90
    }
}

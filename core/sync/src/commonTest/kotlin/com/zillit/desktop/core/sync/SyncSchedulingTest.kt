package com.zillit.desktop.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The ordering rules, pinned without a store or a clock. */
class SyncSchedulingTest {

    private val scope = SyncScope("u1", "p1")

    private fun op(
        id: String,
        state: SyncState = SyncState.Pending,
        group: String? = null,
        dependsOn: String? = null,
        due: Long = 0,
        created: Long = 0,
    ) = SyncOperation(
        id = id, scope = scope, kind = "k", label = id, payload = "{}",
        groupKey = group, dependsOn = dependsOn, state = state,
        nextAttemptAt = due, createdAt = created, updatedAt = created,
    )

    @Test
    fun `the oldest due pending operation runs first`() {
        val ops = listOf(op("a", created = 1), op("b", created = 2))
        assertEquals("a", ops.nextRunnable(now = 10)?.id)
    }

    @Test
    fun `an operation waits for its backoff`() {
        val ops = listOf(op("a", due = 50), op("b", due = 0, created = 1))
        assertEquals("b", ops.nextRunnable(now = 10)?.id)
        assertEquals(50, ops.nextWakeAt(now = 10))
    }

    @Test
    fun `a group runs strictly in order and a failed head holds it`() {
        val healthy = listOf(op("create", group = "g"), op("submit", group = "g", created = 1))
        assertEquals("create", healthy.nextRunnable(0)?.id)

        val headFailed =
            listOf(op("create", state = SyncState.Failed, group = "g"), op("submit", group = "g", created = 1))
        assertNull(headFailed.nextRunnable(0), "the submit must not overtake its failed create")

        val headDone = listOf(op("create", state = SyncState.Done, group = "g"), op("submit", group = "g", created = 1))
        assertEquals("submit", headDone.nextRunnable(0)?.id)
    }

    @Test
    fun `an in-flight head also holds its group but not other groups`() {
        val ops = listOf(
            op("g1-a", state = SyncState.InFlight, group = "g1"),
            op("g1-b", group = "g1", created = 1),
            op("g2-a", group = "g2", created = 2),
        )
        assertEquals("g2-a", ops.nextRunnable(0)?.id)
    }

    @Test
    fun `a dependency must be done, not merely first`() {
        val waiting = listOf(op("a", state = SyncState.Failed), op("b", dependsOn = "a", created = 1))
        assertNull(waiting.nextRunnable(0))
        val ready = listOf(op("a", state = SyncState.Done), op("b", dependsOn = "a", created = 1))
        assertEquals("b", ready.nextRunnable(0)?.id)
    }

    @Test
    fun `dependents are found transitively for a discard`() {
        val ops = listOf(
            op("a"),
            op("b", dependsOn = "a", created = 1),
            op("c", dependsOn = "b", created = 2),
            op("d", created = 3),
        )
        assertEquals(setOf("b", "c"), ops.dependentsOf("a"))
        assertEquals(emptySet(), ops.dependentsOf("d"))
    }
}

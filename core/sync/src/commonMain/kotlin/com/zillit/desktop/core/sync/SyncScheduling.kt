package com.zillit.desktop.core.sync

/**
 * The ordering rules, as pure functions over a scope's operations.
 *
 * Kept apart from the engine so they can be pinned in tests without a store,
 * a clock or a coroutine — and read in one place: an operation runs when it
 * is pending, due, first in its group, and its dependency is done.
 *
 * Every function takes the list **in creation order**, as [OutboxStore]
 * returns it. Two operations enqueued in the same millisecond — a create and
 * its submit, typically — are told apart by insertion, which a timestamp
 * cannot do; so the order of the list is the order, and nothing here sorts.
 */

/** The operation that should run next, or null when nothing can. */
fun List<SyncOperation>.nextRunnable(now: Long): SyncOperation? {
    val byId = associateBy { it.id }
    val headOfGroup = filter { it.isOpen && it.groupKey != null }
        .groupBy { it.groupKey }
        .mapValues { (_, members) -> members.first() }
    return firstOrNull { op ->
        op.state == SyncState.Pending &&
            op.nextAttemptAt <= now &&
            // Strict FIFO inside a group: only the oldest open member may run,
            // and if that member is failed or in flight the group waits.
            (op.groupKey == null || headOfGroup[op.groupKey] === op) &&
            (op.dependsOn == null || byId[op.dependsOn]?.state == SyncState.Done)
    }
}

/**
 * When the engine should look again of its own accord: the earliest future
 * attempt among pending operations, or null when nothing is waiting on time.
 */
fun List<SyncOperation>.nextWakeAt(now: Long): Long? =
    filter { it.state == SyncState.Pending && it.nextAttemptAt > now }
        .minOfOrNull { it.nextAttemptAt }

/**
 * Everything that depends on [id], transitively — what discarding it must
 * also discard, since an operation whose dependency is gone can never run.
 */
fun List<SyncOperation>.dependentsOf(id: String): Set<String> {
    val found = linkedSetOf<String>()
    var frontier = setOf(id)
    while (frontier.isNotEmpty()) {
        val next = filter { it.dependsOn in frontier && it.id !in found }.map { it.id }.toSet()
        found += next
        frontier = next
    }
    return found
}

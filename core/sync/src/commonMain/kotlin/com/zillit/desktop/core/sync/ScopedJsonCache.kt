package com.zillit.desktop.core.sync

/** A cached answer and when it was fetched. */
data class CachedJson(val json: String, val fetchedAt: Long)

/**
 * The last good answer to a read, kept per production so a list can be shown
 * when the network is gone — the orders you raised, the vendors, the week's
 * allowance catalogue.
 *
 * Lives in the durable store beside the drafts, under a reserved kind prefix,
 * because it is small, per production, and must survive the cache database
 * being rebuilt. It is still a cache: nothing here is the only copy of
 * anything, and the sign-out count leaves it out.
 */
class ScopedJsonCache(private val drafts: DraftStore) {

    suspend fun put(scope: SyncScope, name: String, json: String, now: Long) {
        drafts.save(
            LocalDraft(id = id(scope, name), scope = scope, kind = KIND_PREFIX + name, payload = json, updatedAt = now),
        )
    }

    suspend fun get(scope: SyncScope, name: String): CachedJson? =
        drafts.get(id(scope, name))?.let { CachedJson(json = it.payload, fetchedAt = it.updatedAt) }

    private fun id(scope: SyncScope, name: String) = "cache:${scope.userId}:${scope.projectId}:$name"

    companion object {
        /** Rows whose kind starts with this are caches, not drafts. */
        const val KIND_PREFIX = "cache."
    }
}

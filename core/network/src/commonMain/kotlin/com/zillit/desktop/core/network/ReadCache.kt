package com.zillit.desktop.core.network

/** Who a cached answer belongs to. Both may be blank before sign-in / outside a production. */
data class ReadScope(val userId: String, val projectId: String)

/** A cached envelope and when it was fetched. */
data class CachedRead(val body: String, val fetchedAt: Long)

/**
 * The last good answer to a read, kept so it can be shown when the network is
 * gone.
 *
 * Deliberately dumb: [ApiClient] decides what is cacheable and builds the
 * key; the store only remembers. Every key already carries the user and the
 * production, so one production's answers can never surface in another —
 * the same identity that signs the request scopes the cache.
 */
interface ReadCache {
    suspend fun get(key: String): CachedRead?

    suspend fun put(key: String, scope: ReadScope, body: String, fetchedAt: Long)
}

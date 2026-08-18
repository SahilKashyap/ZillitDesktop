package com.zillit.desktop.core.sync

import com.zillit.desktop.core.database.sync.SyncDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A form's unfinished words, kept across restarts. */
data class LocalDraft(
    val id: String,
    val scope: SyncScope,
    val kind: String,
    val payload: String,
    val updatedAt: Long,
) {
    /** Never prints the payload — a production's figures are content. */
    override fun toString(): String = "LocalDraft(id=$id, kind=$kind, updatedAt=$updatedAt)"
}

/**
 * Where a module keeps drafts that must survive a restart or a lost
 * connection. The payload is the module's own JSON; [kind] names the form.
 *
 * Alongside the outbox rather than in it: a draft is something the user is
 * still editing, an operation is something they have decided to send.
 */
interface DraftStore {
    suspend fun save(draft: LocalDraft)

    suspend fun get(id: String): LocalDraft?

    /** Newest first. */
    suspend fun forKind(scope: SyncScope, kind: String): List<LocalDraft>

    suspend fun delete(id: String)

    /** How many drafts this person has, across productions — the sign-out question. */
    suspend fun countForUser(userId: String): Int
}

class SqlDraftStore(
    database: SyncDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DraftStore {

    private val queries = database.localDraftQueries

    override suspend fun save(draft: LocalDraft): Unit = withContext(dispatcher) {
        queries.upsert(
            id = draft.id,
            userId = draft.scope.userId,
            projectId = draft.scope.projectId,
            kind = draft.kind,
            payload = draft.payload,
            updatedAt = draft.updatedAt,
        )
    }

    override suspend fun get(id: String): LocalDraft? = withContext(dispatcher) {
        queries.selectById(id).executeAsOneOrNull()?.let {
            LocalDraft(it.id, SyncScope(it.userId, it.projectId), it.kind, it.payload, it.updatedAt)
        }
    }

    override suspend fun forKind(scope: SyncScope, kind: String): List<LocalDraft> = withContext(dispatcher) {
        queries.selectForKind(scope.userId, scope.projectId, kind).executeAsList().map {
            LocalDraft(it.id, SyncScope(it.userId, it.projectId), it.kind, it.payload, it.updatedAt)
        }
    }

    override suspend fun delete(id: String): Unit = withContext(dispatcher) { queries.deleteById(id) }

    override suspend fun countForUser(userId: String): Int = withContext(dispatcher) {
        queries.countForUser(userId).executeAsOne().toInt()
    }
}

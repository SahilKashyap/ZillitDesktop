package com.zillit.desktop.core.database

import com.zillit.desktop.core.database.sync.SyncDatabase

/**
 * Sign-out's local wipe: every row of the last person's, in one transaction
 * per database.
 *
 * Android drops the lot on logout — `SharedPref.clearSharedPref()` and a
 * Realm `deleteAll()` (`LocalDataEraser.kt:57-145`) — and the desktop has to
 * do the same or the next person to sign in on this computer opens onto the
 * previous one's boards, threads and mail. The keychain key goes with the
 * session too, so a fresh launch could not read these files anyway; this is
 * for the *running* app, whose open connections would otherwise keep serving
 * what they had.
 *
 * Labels are left alone: they belong to a language, not a person. Screenplays
 * (Zillit Draft) are the person's own documents and are handled by that tool.
 */
class LocalCacheWiper(
    private val database: ZillitDatabase?,
    private val syncDatabase: SyncDatabase?,
) {
    fun wipe() {
        database?.transaction {
            with(database.projectCacheQueries) {
                deleteAllProfiles(); deleteAllProjects(); deleteAllProjectUsers(); deleteAllTools(); deleteAllNotices()
            }
            with(database.chatCacheQueries) { deleteAllMessages(); deleteAllThreadReads() }
            with(database.emailCacheQueries) { deleteAllEmails(); deleteAllFolders() }
            database.readCacheQueries.deleteAll()
            database.notificationLedgerQueries.deleteAll()
            database.projectListCacheQueries.deleteProjectList()
        }
        // Unsent operations and drafts are the person's too — the sign-out
        // dialog counts them and says they will be lost.
        syncDatabase?.transaction {
            syncDatabase.outboxQueries.deleteAll()
            syncDatabase.localDraftQueries.deleteAll()
        }
    }
}

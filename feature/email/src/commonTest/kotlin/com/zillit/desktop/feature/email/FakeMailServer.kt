package com.zillit.desktop.feature.email

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.domain.ContactRepository
import com.zillit.desktop.feature.email.domain.DraftRepository
import com.zillit.desktop.feature.email.domain.EmailContact
import com.zillit.desktop.feature.email.domain.EmailSignature
import com.zillit.desktop.feature.email.domain.FolderRepository
import com.zillit.desktop.feature.email.domain.SignatureRepository
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.EmailRepository
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.OutgoingEmail
import kotlinx.coroutines.CompletableDeferred

/**
 * One fake mail server for every test in this module.
 *
 * Shared rather than redeclared per test file: three near-identical copies had
 * already started drifting in what they recorded, which is how a test ends up
 * asserting against a fake that no longer resembles the real thing.
 *
 * Everything is a plain `var` so a test can state its scenario in one line and
 * read the recorded calls afterwards.
 */
class FakeMailServer(
    /** The uids the mailbox will report for every folder. */
    var uids: List<Int> = listOf(1, 2, 3),
    /** Base64 the attachment endpoint returns. `SGVsbG8=` is "Hello". */
    var attachmentPayload: String = "SGVsbG8=",
    var folderNames: List<String> = listOf(EmailFolder.INBOX, EmailFolder.DRAFTS),
) : EmailRepository, DraftRepository, ContactRepository, FolderRepository, SignatureRepository {

    // -- recorded ----------------------------------------------------------

    var uidCalls = 0
        private set
    var folderCalls = 0
        private set
    var attachmentCalls = 0
        private set

    /** Every uid batch requested, in order. */
    val indexed = mutableListOf<List<Int>>()

    var sent: OutgoingEmail? = null
        private set
    val savedDrafts = mutableListOf<OutgoingEmail>()
    val updatedDrafts = mutableListOf<Pair<String, OutgoingEmail>>()
    val deletedDraftIds = mutableListOf<String>()

    /** One entry per `move` call — the point of the move tests. */
    data class Move(val ids: List<String>, val from: String, val to: String)

    val moves = mutableListOf<Move>()
    val destroyed = mutableListOf<List<String>>()
    var trashEmptied = false
        private set

    // -- scenario ----------------------------------------------------------

    /** Set to hold `index` open, so a sync can be caught mid-flight. */
    var indexGate: CompletableDeferred<Unit>? = null

    /** Set to hold `saveDraft` open, so a save can be caught mid-flight. */
    var draftGate: CompletableDeferred<Unit>? = null

    var attachmentFails = false
    var moveFails = false
    var draftSaveFails = false

    /** Drafts the server already holds. */
    var storedDrafts: List<EmailDraft> = emptyList()

    /** Ids handed out by `saveDraft`, in order. */
    var newDraftIds: MutableList<String> = mutableListOf("draft-1", "draft-2", "draft-3")

    // -- EmailRepository ---------------------------------------------------

    override suspend fun folders(): ZillitResult<List<EmailFolder>> {
        folderCalls++
        return ZillitResult.Success(folderNames.map { EmailFolder(it, isSystem = true) })
    }

    override suspend fun folderUids(folderName: String): ZillitResult<List<Int>> {
        uidCalls++
        return ZillitResult.Success(uids)
    }

    override suspend fun index(folderName: String, uids: List<Int>): ZillitResult<List<EmailSummary>> {
        indexed += uids
        indexGate?.await()
        return ZillitResult.Success(uids.map { summary(it, folderName) })
    }

    override suspend fun trail(folderName: String, messageIds: List<String>) =
        ZillitResult.Success(emptyList<EmailMessage>())

    override suspend fun send(message: OutgoingEmail): ZillitResult<Unit> {
        sent = message
        return ZillitResult.Success(Unit)
    }

    override suspend fun attachment(
        attachmentId: String,
        messageId: String,
        folderName: String,
    ): ZillitResult<String> {
        attachmentCalls++
        return if (attachmentFails) {
            ZillitResult.Failure(ZillitError.Http(404, "Attachment not found"))
        } else {
            ZillitResult.Success(attachmentPayload)
        }
    }

    override suspend fun move(
        messageIds: List<String>,
        fromFolder: String,
        toFolder: String,
    ): ZillitResult<Unit> {
        moves += Move(messageIds, fromFolder, toFolder)
        if (moveFails) return ZillitResult.Failure(ZillitError.Http(500, "nope"))
        // The server really does move them, so the next uid sync reflects it.
        uids = uids.filterNot { "m$it" in messageIds.toSet() }
        return ZillitResult.Success(Unit)
    }

    override suspend fun deletePermanently(messageIds: List<String>): ZillitResult<Unit> {
        destroyed += messageIds
        uids = uids.filterNot { "m$it" in messageIds.toSet() }
        return ZillitResult.Success(Unit)
    }

    override suspend fun emptyTrash(): ZillitResult<Unit> {
        trashEmptied = true
        uids = emptyList()
        return ZillitResult.Success(Unit)
    }

    // -- FolderRepository --------------------------------------------------

    val createdFolders = mutableListOf<String>()
    val renamedFolders = mutableListOf<Pair<String, String>>()
    var folderWriteFails = false

    override suspend fun createFolder(name: String): ZillitResult<Unit> {
        if (folderWriteFails) return ZillitResult.Failure(ZillitError.Http(409, "already exists"))
        createdFolders += name
        folderNames = folderNames + name
        return ZillitResult.Success(Unit)
    }

    override suspend fun renameFolder(name: String, newName: String): ZillitResult<Unit> {
        if (folderWriteFails) return ZillitResult.Failure(ZillitError.Http(409, "already exists"))
        renamedFolders += name to newName
        folderNames = folderNames.map { if (it == name) newName else it }
        return ZillitResult.Success(Unit)
    }

    val deletedFolders = mutableListOf<String>()

    override suspend fun deleteFolder(name: String): ZillitResult<Unit> {
        if (folderWriteFails) return ZillitResult.Failure(ZillitError.Http(500, "in use"))
        deletedFolders += name
        folderNames = folderNames.filterNot { it == name }
        return ZillitResult.Success(Unit)
    }

    // -- SignatureRepository -----------------------------------------------

    var storedSignatures: List<EmailSignature> = emptyList()
    val createdSignatures = mutableListOf<Pair<String, String>>()
    val usageUpdates = mutableListOf<Triple<String, Boolean, Boolean>>()
    val deletedSignatures = mutableListOf<String>()

    override suspend fun signatures() = ZillitResult.Success(storedSignatures)

    override suspend fun create(title: String, body: String): ZillitResult<EmailSignature> {
        createdSignatures += title to body
        val created = EmailSignature("sig-new", title, body)
        storedSignatures = storedSignatures + created
        return ZillitResult.Success(created)
    }

    override suspend fun update(id: String, title: String, body: String) = ZillitResult.Success(Unit)

    override suspend fun setUsage(
        id: String,
        useForNew: Boolean,
        useForReply: Boolean,
    ): ZillitResult<Unit> {
        usageUpdates += Triple(id, useForNew, useForReply)
        // Applied, like the real server would: the manager reloads after
        // setting flags, so a fake that ignored the write would hand back the
        // old state and look like a bug in the client.
        storedSignatures = storedSignatures.map {
            if (it.id == id) it.copy(useForNew = useForNew, useForReply = useForReply) else it
        }
        return ZillitResult.Success(Unit)
    }

    override suspend fun delete(id: String): ZillitResult<Unit> {
        deletedSignatures += id
        storedSignatures = storedSignatures.filterNot { it.id == id }
        return ZillitResult.Success(Unit)
    }

    // -- ContactRepository -------------------------------------------------

    /** The address book this fake hands back. */
    var storedContacts: List<EmailContact> = emptyList()

    override suspend fun contacts() = ZillitResult.Success(storedContacts)

    // -- DraftRepository ---------------------------------------------------

    override suspend fun drafts(beforeMillis: Long) = ZillitResult.Success(storedDrafts)

    override suspend fun saveDraft(message: OutgoingEmail): ZillitResult<String> {
        draftGate?.await()
        if (draftSaveFails) return ZillitResult.Failure(ZillitError.Http(500, "nope"))
        savedDrafts += message
        return ZillitResult.Success(newDraftIds.removeFirstOrNull() ?: "draft-x")
    }

    override suspend fun updateDraft(draftId: String, message: OutgoingEmail): ZillitResult<Unit> {
        if (draftSaveFails) return ZillitResult.Failure(ZillitError.Http(500, "nope"))
        updatedDrafts += draftId to message
        return ZillitResult.Success(Unit)
    }

    override suspend fun deleteDrafts(draftIds: List<String>): ZillitResult<Unit> {
        deletedDraftIds += draftIds
        return ZillitResult.Success(Unit)
    }

    private fun summary(uid: Int, folderName: String) = EmailSummary(
        id = "m$uid",
        threadId = "t$uid",
        subject = "Subject $uid",
        from = "a@b.com",
        receivedAtMillis = uid.toLong(),
        uid = uid,
        folderName = folderName,
    )
}

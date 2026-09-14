package com.zillit.desktop.core.badges

/**
 * What one read clears in the ledger — Android's `markReadCommon` variants,
 * one per surface (`CommonBadgesHandler.kt`, cited per case).
 *
 * None carries a time cutoff: Android flips every unread row the scope
 * names, whenever it was written.
 */
sealed interface LedgerRead {

    fun matches(row: NotificationRecord): Boolean

    /**
     * A board or unit opened — `Home`/`Default`:
     * `!isGlobal && (unit == id || chatUnitId == id)` (5396-5414, 8093-8100).
     */
    data class Board(val unitId: String) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean =
            !row.isGlobal && (row.unit == unitId || row.chatUnitId == unitId)
    }

    /** A tool fronted — `Info`, `Production`, `MissedCall`…: `tool == label` (5568-5584, 6031-6043). */
    data class Tool(val wireLabel: String) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean = row.tool == wireLabel
    }

    /** A whole area — `SosBadge`, `GlobalRead`: `section == key` (7356-7365, 7434-7444). */
    data class Section(val section: String) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean = row.section == section
    }

    /**
     * One entity — an email, an approval request, an SOS alert:
     * `referenceId == id` (6054-6070), or the row's own ids.
     */
    data class Reference(val id: String, val unit: String? = null) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean =
            (row.referenceId == id || row.id == id || row.mongoId == id) && (unit == null || row.unit == unit)
    }

    /** A conversation read — `markReadyByChatRoomIdSenderId`: room, sender or reference (2072-2109). */
    data class Conversation(val key: String) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean =
            row.chatRoomId == key || row.senderId == key || row.referenceId == key
    }

    /** A tool's tab — `markReadCommon(isLCW = true)`: every level given is an equality (1442-1474). */
    data class Levels(
        val tool: String,
        val unit: String? = null,
        val level1: String? = null,
        val level2: String? = null,
        val level3: String? = null,
        val action: String? = null,
    ) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean =
            row.tool == tool &&
                unit.isNullOr(row.unit) && level1.isNullOr(row.level1) && level2.isNullOr(row.level2) &&
                level3.isNullOr(row.level3) && action.isNullOr(row.action)

        private fun String?.isNullOr(value: String): Boolean = this == null || this == value
    }

    /** The calendar opened — `markReadExpiredEvents`: every invite whose event has ended (2028-2070). */
    data class CalendarExpired(val nowMillis: Long) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean =
            row.tool == NotificationRecord.CALENDAR_TOOL && (row.calendarEnd ?: Long.MAX_VALUE) < nowMillis
    }

    /**
     * One mail opened — the phones' email read (Android `EmailInbox`
     * Subtract then `markReadCommon(unit, referenceId)` 5797-5834, iOS
     * `updateMarkReadBySectionUnitAndReferenceIds`): an `email_label` row
     * filed under the folder (`unit`) whose `reference_id` is the mail's
     * IMAP uid — or, from the older writers, its message id.
     *
     * Uids restart at 1 in every folder and every mailbox, so the folder is
     * part of the key and so is the mailbox (`level_1`, ZL-21025): a row
     * tagged with another mailbox's address is never this read's. An
     * untagged row, or an unknown own address, fails open the way the phones
     * do — a read must never leave its own badge stuck.
     */
    data class Mail(
        val folder: String,
        val uid: Int,
        val messageId: String = "",
        val mailbox: String? = null,
        /**
         * Whether rows without a mailbox tag are this read's. They predate the
         * stamp and are the person's own mail, so the personal mailbox owns
         * them and the shared Accounts mailbox does not.
         */
        val ownsUntagged: Boolean = true,
    ) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean =
            row.isMailIn(folder, mailbox, ownsUntagged) &&
                (row.referenceId == uid.toString() || (messageId.isNotBlank() && row.namesMessage(messageId)))
    }

    /**
     * A folder synced against the mail server — retires rows that no longer
     * describe unread mail. See [MailFolderState].
     */
    data class MailFolder(val state: MailFolderState) : LedgerRead {
        override fun matches(row: NotificationRecord): Boolean =
            row.isMailIn(state.folder, state.mailbox, state.ownsUntagged) && state.retires(row)
    }
}

/**
 * A folder as the mail server just reported it, in the ledger's terms.
 *
 * Android ZL-21196 (`clearBadgesForDepartedEmails`): `get-folder-uids` is the
 * folder's complete list, so a uid the ledger holds that the server no longer
 * lists has left the folder — moved by a rule or from another device, or
 * deleted — and its badge goes with it. The web goes further and takes the
 * badge rows as the read state (`updateEmailReadStatusInDb`); here it runs
 * the sane way round: mail the mailbox reports read is not unread mail,
 * whatever the ledger says. Both rules are what keeps the Email badge equal
 * to the unread mail the desktop can actually show.
 */
data class MailFolderState(
    val folder: String,
    /** Every uid the folder holds — the server's complete list. */
    val uids: Set<Int>,
    /** Uids the mailbox reports read. */
    val readUids: Set<Int> = emptySet(),
    /** Message ids the mailbox reports read — rows from older writers key by these. */
    val readMessageIds: Set<String> = emptySet(),
    /** Message ids of every mail held for the folder; trusted only when [complete]. */
    val messageIds: Set<String> = emptySet(),
    /** Whether every uid the server lists is held locally. */
    val complete: Boolean = false,
    /**
     * When the uid list was asked for. A row written after that describes
     * mail the list could not have contained yet — a mail landing during a
     * sync — so it is not judged departed; the sync its arrival triggers
     * will. [ARRIVAL_GRACE_MILLIS] covers the clocks disagreeing.
     */
    val listedAt: Long = Long.MAX_VALUE,
    /** This mailbox's address, as the rows carry it in `level_1`; null when unknown. */
    val mailbox: String? = null,
    /** Whether untagged rows are this mailbox's — see [LedgerRead.Mail.ownsUntagged]. */
    val ownsUntagged: Boolean = true,
) {
    /** Whether one row, already known to be this folder's, is no longer a badge. */
    fun retires(row: NotificationRecord): Boolean {
        val key = row.referenceId
        if (key.isBlank()) return false
        val uid = key.toIntOrNull()
        val settled = row.created < listedAt - ARRIVAL_GRACE_MILLIS
        return when {
            uid != null -> uid in readUids || (settled && uid !in uids)
            key in readMessageIds -> true
            else -> settled && complete && key !in messageIds
        }
    }

    companion object {
        const val ARRIVAL_GRACE_MILLIS = 2 * 60 * 1000L
    }
}

/**
 * Whether a row is an email row of one folder in one mailbox. The folder
 * compares case-insensitively — `INBOX` is by RFC 3501, and the service has
 * spelled the others both ways.
 */
private fun NotificationRecord.isMailIn(folder: String, mailbox: String?, ownsUntagged: Boolean): Boolean =
    section == BadgeSections.EMAIL &&
        unit.trim().equals(folder.trim(), ignoreCase = true) &&
        isMailOf(mailbox, ownsUntagged)

/**
 * Whether an email row is one mailbox's.
 *
 * The service stamps the receiving mailbox's address in `level_1` (ZL-21025):
 * a person's own, or the production's shared Accounts mailbox their
 * department may open. Untagged rows predate the stamp and are the person's
 * own, so they are the personal mailbox's ([ownsUntagged]) and nobody else's;
 * an unknown address ([mailbox] null) admits every row, so a read never
 * leaves its own badge stuck. Addresses compare case-insensitively, trimmed.
 */
fun NotificationRecord.isMailOf(mailbox: String?, ownsUntagged: Boolean = true): Boolean = when {
    level1.isBlank() -> ownsUntagged || mailbox.isNullOrBlank()
    mailbox.isNullOrBlank() -> true
    else -> level1.sameAddress(mailbox)
}

/**
 * Whether a row may be counted for a production whose openable mailboxes are
 * [mailboxes]: rows of other sections always; untagged mail always (it is the
 * person's own); tagged mail when its mailbox is one this desktop can open. A
 * production with no mailbox named yet counts everything.
 */
fun NotificationRecord.isOfMailboxes(mailboxes: Set<String>?): Boolean =
    section != BadgeSections.EMAIL ||
        level1.isBlank() ||
        mailboxes.isNullOrEmpty() ||
        mailboxes.any { level1.sameAddress(it) }

internal fun String.sameAddress(other: String): Boolean = trim().equals(other.trim(), ignoreCase = true)

private fun NotificationRecord.namesMessage(messageId: String): Boolean =
    referenceId == messageId || id == messageId || mongoId == messageId

/**
 * The `notification:silent` instruction — what another device, or the
 * server, has already dropped. Every key Android's `markReadByNotificationId`
 * handles (`AppBadgeDBManager.kt:1772-1967`); the phones apply it to their
 * own rows because the server never applies it to its.
 */
data class BadgeSilence(
    /** `read_notification_ids` (and the frame's own uuid) — rows read elsewhere. */
    val readIds: Set<String> = emptySet(),
    /** `deleted_chat_ids` — chat messages since deleted. */
    val deletedChatIds: Set<String> = emptySet(),
    /** `deleted_comment_ids` — unit-chat comments since deleted. */
    val deletedCommentIds: Set<String> = emptySet(),
    /** `chat_room_no_access` — rooms left, deleted or kicked from. */
    val lostRooms: Set<String> = emptySet(),
    /** `tools_no_view_access` — wire tool labels this person may no longer see. */
    val lostTools: Set<String> = emptySet(),
    /** `unit_no_view_access` — units likewise. */
    val lostUnits: Set<String> = emptySet(),
    /** `admin_settings_no_access` — admin rights taken away; matches unit or tool. */
    val lostAdminSettings: Set<String> = emptySet(),
    /** `reference_data.self_device_id` — a frame from this very device is not news. */
    val selfDeviceId: String? = null,
    val projectId: String? = null,
) {
    val isEmpty: Boolean
        get() = readIds.isEmpty() && deletedChatIds.isEmpty() && deletedCommentIds.isEmpty() &&
            lostRooms.isEmpty() && lostTools.isEmpty() && lostUnits.isEmpty() && lostAdminSettings.isEmpty()

    private val byId: Set<String> get() = readIds + deletedChatIds + deletedCommentIds

    /** Whether one row is something this instruction drops. */
    fun matches(row: NotificationRecord): Boolean =
        namesRow(row) || row.level3 in lostRooms || row.chatRoomId in lostRooms || losesAccess(row)

    private fun namesRow(row: NotificationRecord): Boolean =
        row.id in byId || row.referenceId in byId || row.mongoId in byId

    private fun losesAccess(row: NotificationRecord): Boolean =
        row.tool in lostTools || row.unit in lostUnits || row.unit in lostAdminSettings || row.tool in lostAdminSettings
}

/** Reads a `notification:silent` frame; null when it carries no instruction. */
fun badgeSilenceFrom(payload: kotlinx.serialization.json.JsonElement?): BadgeSilence? {
    val record = payload.wireRecords().firstOrNull() ?: return null
    val reference = record.referenceData() ?: record
    val silence = BadgeSilence(
        readIds = reference.strings("read_notification_ids").ifEmpty { setOfNotNull(record.text("notification_uuid")) },
        deletedChatIds = reference.strings("deleted_chat_ids") + record.strings("deleted_chat_ids"),
        deletedCommentIds = reference.strings("deleted_comment_ids"),
        lostRooms = reference.strings("chat_room_no_access") + record.strings("chat_room_no_access"),
        lostTools = reference.strings("tools_no_view_access"),
        lostUnits = reference.strings("unit_no_view_access"),
        lostAdminSettings = reference.strings("admin_settings_no_access"),
        selfDeviceId = reference.text("self_device_id"),
        projectId = record.text("project_id"),
    )
    return silence.takeUnless { it.isEmpty }
}

/**
 * The records a `notification:save` frame carries, minus the ones no client
 * counts: a silent push, a row flagged `ignore`, this person's own action
 * (Android `insertOrUpdate` 81-168, web `AllBadges.jsx:338-341`).
 */
fun ledgerArrivalsFrom(
    payload: kotlinx.serialization.json.JsonElement?,
    fallbackProjectId: String? = null,
): List<NotificationRecord> =
    payload.wireRecords()
        .mapNotNull { notificationRecordFrom(it, fromApi = false, fallbackProjectId = fallbackProjectId) }
        .filterNot { it.silent || it.ignored || it.self }

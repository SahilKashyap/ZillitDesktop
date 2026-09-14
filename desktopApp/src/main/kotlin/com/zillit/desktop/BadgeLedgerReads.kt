package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.core.badges.BadgeStore
import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.badges.MailFolderState
import com.zillit.desktop.core.badges.isMailOf
import com.zillit.desktop.core.badges.NotificationRecord
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HeaderContext
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.email.data.MailboxProfileSource
import com.zillit.desktop.feature.email.ui.MailFolderSync
import com.zillit.desktop.feature.email.ui.MailRead
import com.zillit.desktop.feature.notifications.domain.NotificationsRepository
import com.zillit.desktop.feature.sos.domain.SosRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Which ledger rows a segment read clears — Android's `segmentProvider`
 * (`CommonBadgesHandler.kt:9927-10006`) collapsed to the surfaces this
 * desktop reads through [emitSegmentRead]: one entity when a reference is
 * named (an email), a whole area for an area label, the missed-call tool
 * for the Calls tab, else the unit — a board, a distribution list, a
 * transport queue.
 */
internal fun ledgerReadFor(segment: String, referenceId: String?): LedgerRead = when {
    referenceId != null -> LedgerRead.Reference(referenceId)
    segment in BadgeSections.all -> LedgerRead.Section(segment)
    segment == NotificationRecord.CALL_TOOL -> LedgerRead.Tool(segment)
    else -> LedgerRead.Board(segment)
}

/**
 * The SOS feed's reads, applied to the ledger too.
 *
 * Android `SosBadge`: every `sos_label` row on a feed fetch or a clear-all;
 * one alert deleted is one row, named by its `_id`.
 */
internal fun SosRepository.readingLedger(store: BadgeStore): SosRepository = object : SosRepository by this {
    override suspend fun markRead(timestampMillis: Long): ZillitResult<Unit> =
        this@readingLedger.markRead(timestampMillis).also { store.markRead(LedgerRead.Section(BadgeSections.SOS)) }

    override suspend fun deleteAlert(alertId: String, timestampMillis: Long): ZillitResult<Unit> =
        this@readingLedger.deleteAlert(alertId, timestampMillis)
            .also { store.markRead(LedgerRead.Reference(alertId)) }

    override suspend fun deleteAllAlerts(timestampMillis: Long): ZillitResult<Unit> =
        this@readingLedger.deleteAllAlerts(timestampMillis)
            .also { store.markRead(LedgerRead.Section(BadgeSections.SOS)) }
}

/**
 * The bell page's reads, applied to the ledger too.
 *
 * Android `GlobalRead`: every row of the segment; one row deleted is one
 * row, named by its `_id`.
 */
internal fun NotificationsRepository.readingLedger(store: BadgeStore): NotificationsRepository =
    object : NotificationsRepository by this {
        override suspend fun markRead(segment: String, timestampMillis: Long): ZillitResult<Unit> =
            this@readingLedger.markRead(segment, timestampMillis).also { store.markRead(LedgerRead.Section(segment)) }

        override suspend fun delete(notificationId: String, timestampMillis: Long): ZillitResult<Unit> =
            this@readingLedger.delete(notificationId, timestampMillis)
                .also { store.markRead(LedgerRead.Reference(notificationId)) }

        override suspend fun deleteAll(): ZillitResult<Unit> =
            this@readingLedger.deleteAll().also { store.markRead(LedgerRead.Section(BadgeSections.GLOBAL)) }
    }

/**
 * The mailbox's reads, applied to the ledger — and the ledger's mail
 * counts, cut the way the mailbox draws them.
 *
 * An email row is keyed by folder and IMAP uid, tagged with the mailbox
 * address in `level_1` — see `LedgerRead.Mail`. Every entry point takes the
 * address of the mailbox the module is showing (the person's own, or the
 * production's shared Accounts mailbox); untagged rows predate the tag and
 * are the person's own. Null means the module does not know yet, which reads
 * as the personal mailbox and fails open where the address is unknown too.
 */
internal class MailLedger(
    private val store: BadgeStore,
    private val scopes: MailboxScopes,
) {
    private var lastReport = ""

    /** A message opened for reading: its row clears. */
    suspend fun read(read: MailRead, mailbox: String? = null): Int {
        val scope = scopeOf(mailbox)
        return store.markRead(
            LedgerRead.Mail(read.folderName, read.uid, read.messageId, scope.address, scope.ownsUntagged),
        )
    }

    /**
     * A folder synced against the server: rows that no longer describe unread
     * mail retire — read on another device, moved by a rule, deleted — which
     * is what keeps the Email badge equal to the unread mail on screen.
     */
    suspend fun synced(sync: MailFolderSync, mailbox: String? = null): Int {
        val scope = scopeOf(mailbox)
        val retired = store.markRead(LedgerRead.MailFolder(sync.toLedgerState(scope)))
        if (retired > 0) ZillitLog.d(TAG) { "email rows retired by ${sync.folderName} sync: $retired" }
        report()
        return retired
    }

    /** Unread per folder of one mailbox — the folder list's badges. */
    suspend fun folderBadges(mailbox: String?): Map<String, Int> {
        val scope = scopeOf(mailbox)
        return unreadMail().filter { it.isMailOf(scope.address, scope.ownsUntagged) }
            .groupingBy { it.unit }.eachCount()
    }

    /**
     * Unread per mailbox address — the switcher's pills, and its dot for the
     * mailbox not in view. Untagged rows count under the personal address;
     * tagged rows under the learnt address they match, else as tagged.
     */
    fun mailboxUnread(): Map<String, Int> {
        val personal = scopes.personal(store.openProjectId)
        val known = listOfNotNull(personal, scopes.accounts(store.openProjectId))
        return unreadMail().groupingBy { row ->
            when {
                row.level1.isBlank() -> personal.orEmpty()
                else -> known.firstOrNull { row.level1.sameAddress(it) } ?: row.level1.trim()
            }
        }.eachCount()
    }

    private fun unreadMail() = store.unreadRows(BadgeSections.EMAIL, everyMailbox = true)

    /**
     * Which rows a mailbox address names. The personal mailbox owns the
     * untagged rows; the shared Accounts mailbox does not. An address the
     * module has not given yet is the personal one — unknown, it fails open.
     */
    private suspend fun scopeOf(mailbox: String?): MailScope {
        val personal = store.openProjectId?.let { scopes.learn(it) }
        val address = mailbox?.takeIf { it.isNotBlank() } ?: personal
        return MailScope(address, ownsUntagged = personal == null || address == null || address.sameAddress(personal))
    }

    private data class MailScope(val address: String?, val ownsUntagged: Boolean)

    private fun MailFolderSync.toLedgerState(scope: MailScope) = MailFolderState(
        folder = folderName,
        uids = serverUids,
        readUids = messages.filter { it.isRead }.map { it.uid }.toSet(),
        readMessageIds = messages.filter { it.isRead }.map { it.id }.toSet(),
        messageIds = messages.map { it.id }.toSet(),
        complete = complete,
        listedAt = listedAt,
        mailbox = scope.address,
        ownsUntagged = scope.ownsUntagged,
    )

    /**
     * Names the email rows a sync left counting — folder, key, mailbox tag,
     * written when — once per distinct set. A badge that outlives its mail is
     * diagnosed from this line, not from the count.
     */
    private fun report() {
        val counted = store.unreadRows(BadgeSections.EMAIL)
        val held = unreadMail() - counted.toSet()
        val describe = { rows: List<NotificationRecord> ->
            rows.joinToString { "${it.unit}/${it.referenceId}/${it.level1.maskedAddress()}/${it.created}" }
        }
        val line = "email rows still unread: ${counted.size} [${describe(counted)}]" +
            if (held.isEmpty()) "" else "; held for another mailbox: ${held.size} [${describe(held)}]"
        if (line == lastReport) return
        lastReport = line
        ZillitLog.d(TAG) { line }
    }

    private fun String.maskedAddress(): String = when {
        isBlank() -> "-"
        '@' in this -> take(1) + "…" + substring(indexOf('@'))
        else -> take(1) + "…"
    }

    private companion object {
        const val TAG = "Badges"
    }
}

private fun String.sameAddress(other: String): Boolean = trim().equals(other.trim(), ignoreCase = true)

/**
 * One address asked of the server once per production and kept — a read
 * must not cost a request. Re-asked after a failure. The picker asks before
 * any production is open, naming the last one and the person's id on it, as
 * its seed does.
 */
class MailboxAddress(
    private val projectId: () -> String?,
    private val userId: () -> String?,
    private val fetch: suspend (projectId: String?, userId: String?) -> ZillitResult<String?>,
) {
    private val lock = Mutex()
    private var scope: Pair<String?, String?>? = null
    private var resolved = false
    private var address: String? = null

    suspend operator fun invoke(projectId: String? = this.projectId(), userId: String? = this.userId()): String? =
        lock.withLock {
            val asked = projectId to userId
            if (asked != scope) {
                scope = asked
                resolved = false
                address = null
            }
            if (!resolved) {
                // The open production needs no override — its headers already say so.
                val override = projectId.takeIf { it != this.projectId() }
                when (val got = fetch(override, userId.takeIf { override != null })) {
                    is ZillitResult.Success -> {
                        address = got.data?.trim()?.takeIf { it.isNotBlank() }
                        resolved = true
                    }
                    is ZillitResult.Failure -> ZillitLog.w(TAG) { "mailbox address unknown: ${got.error.technical}" }
                }
            }
            address
        }

    companion object {
        private const val TAG = "Badges"

        /** The person's own mailbox: `GET user/profile` → `mail_box_detail.email_address`, as the settings read it. */
        fun personal(apiClient: ApiClient, config: AppConfig, headers: StateFlow<HeaderContext>) = MailboxAddress(
            projectId = { headers.value.projectId },
            userId = { headers.value.userId },
            fetch = { project, user ->
                MailboxProfileSource(apiClient, config)
                    .profile(CallOptions(projectId = project, userId = user))
                    .map { it.credentials?.emailAddress }
            },
        )

        /**
         * The production's shared Accounts mailbox: `GET project/{id}` →
         * `accounts_mail_box_detail.email_address`, which the server fills
         * only for Accounts-department members (`{}` for everyone else — the
         * web's `getAccountsMailboxDetail`).
         */
        fun accounts(apiClient: ApiClient, config: AppConfig, headers: StateFlow<HeaderContext>) = MailboxAddress(
            projectId = { headers.value.projectId },
            userId = { headers.value.userId },
            fetch = { project, user ->
                val id = project ?: headers.value.projectId
                if (id == null) {
                    ZillitResult.Success(null)
                } else {
                    apiClient.request(
                        verb = HttpVerb.Get,
                        url = "${config.apiV2()}project/$id",
                        serializer = JsonElement.serializer(),
                        module = RequestModule.ProjectUser,
                        options = CallOptions(projectId = project, userId = user),
                    ).map { body ->
                        ((body as? JsonObject)?.get("accounts_mail_box_detail") as? JsonObject)
                            ?.get("email_address")?.let { it as? JsonPrimitive }?.contentOrNull
                    }
                }
            },
        )
    }
}

/**
 * Which mailboxes the badges count mail for, production by production.
 *
 * Asked of the server when a production opens ([learn]) and handed to the
 * ledger; remembered in one user-scoped preference so the picker, shown
 * before any production is open, scopes the productions opened before
 * ([restore]). A production never opened here counts every mail row until
 * it is. Sign-out wipes the preference with the rest of the user scope.
 *
 * Two addresses per production: the person's own, and the shared Accounts
 * mailbox their department may open. The Accounts one counts only while
 * [accountsOpenable] says the desktop can show that mailbox — a badge for
 * mail with no view is a badge nothing here could clear.
 */
class MailboxScopes(
    private val store: BadgeStore,
    private val preferences: PreferenceStore,
    private val personalAddress: MailboxAddress,
    private val accountsAddress: MailboxAddress,
    private val accountsOpenable: () -> Boolean = { false },
) {
    constructor(
        apiClient: ApiClient,
        config: AppConfig,
        headers: StateFlow<HeaderContext>,
        store: BadgeStore,
        preferences: PreferenceStore,
        accountsOpenable: () -> Boolean = { false },
    ) : this(
        store,
        preferences,
        MailboxAddress.personal(apiClient, config, headers),
        MailboxAddress.accounts(apiClient, config, headers),
        accountsOpenable,
    )

    private val lock = Mutex()
    private val personal = mutableMapOf<String, String>()
    private val accounts = mutableMapOf<String, String>()

    /** The person's own mailbox on [projectId], once learnt. */
    fun personal(projectId: String?): String? = projectId?.let(personal::get)

    /** The production's Accounts mailbox, once learnt — null when the person is not in that department. */
    fun accounts(projectId: String?): String? = projectId?.let(accounts::get)

    /** Earlier sessions' answers, back into the ledger. */
    suspend fun restore() {
        preferences.get(ZillitPreferences.BadgeMailboxes).lineSequence()
            .map { it.split('\t') }
            .filter { it.size >= 2 && it[0].isNotBlank() }
            .forEach { parts ->
                val project = parts[0]
                parts[1].takeIf { it.isNotBlank() }?.let { personal[project] = it }
                parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { accounts[project] = it }
                store.showMailboxes(project, openable(project))
            }
    }

    /**
     * This person's mailboxes on [projectId], asked once and remembered;
     * answers the personal address. The open production needs no [userId];
     * the picker names the last one's.
     */
    suspend fun learn(projectId: String, userId: String? = null): String? {
        val own = if (userId == null) personalAddress(projectId) else personalAddress(projectId, userId)
        val shared = if (userId == null) accountsAddress(projectId) else accountsAddress(projectId, userId)
        lock.withLock {
            val before = personal.toMap() to accounts.toMap()
            own?.let { personal[projectId] = it }
            shared?.let { accounts[projectId] = it }
            // Written only when something was learnt: every folder sync asks.
            if (before != (personal.toMap() to accounts.toMap())) {
                val lines = (personal.keys + accounts.keys).map { project ->
                    "$project\t${personal[project].orEmpty()}\t${accounts[project].orEmpty()}"
                }
                preferences.set(ZillitPreferences.BadgeMailboxes, lines.joinToString("\n"))
            }
        }
        store.showMailboxes(projectId, openable(projectId))
        return own
    }

    private fun openable(projectId: String): Set<String> =
        setOfNotNull(personal[projectId], accounts[projectId]?.takeIf { accountsOpenable() })
}

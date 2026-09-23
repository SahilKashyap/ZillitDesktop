package com.zillit.desktop.feature.chat.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * One person on the production, as the directory shows them.
 *
 * Crew who asked to keep their name private never reach this type — the host
 * filters them out before the screen sees the list, the same honour Android's
 * members tab pays.
 */
data class CrewContact(
    val userId: String,
    val fullName: String,
    val designation: String? = null,
    val department: String? = null,
    val email: String? = null,
    val isAdmin: Boolean = false,
    /** Their primary device — the address a 1:1 call rings. Null: not callable. */
    val deviceId: String? = null,
    /** When they last used the app — the listing's "Last Entry" line. */
    val lastActiveMillis: Long? = null,
    /**
     * They left or were removed from the production, but their history is
     * still worth opening — Android's Members filter keeps `left` and
     * `removed` alongside the active statuses (`MembersVM.kt:416-420`), and
     * its thread header captions such a peer "Disconnected". The host maps
     * this from `ProjectUser.status` ("left"/"removed"); false covers active
     * crew and older cached rows with no status at all.
     */
    val hasLeft: Boolean = false,
)

/**
 * The designation a listing shows under a name — still a translation key,
 * localised by the caller. The generic `member_label` is membership, not a
 * job, and every Android surface hides it rather than captioning half the
 * crew "Member" (`Constants.MEMBER_TYPE_LABEL`, `BaseViewModel.kt:2065`).
 */
fun CrewContact.designationLabel(): String? =
    designation?.takeIf { it.isNotBlank() && it != MEMBER_DESIGNATION }

const val MEMBER_DESIGNATION = "member_label"

/**
 * The search box's rule: name, role or department, case-blind. A coordinator
 * looking for "the gaffer" should not need to remember who holds the job.
 */
fun List<CrewContact>.searchCrew(query: String): List<CrewContact> {
    val needle = query.trim()
    if (needle.isEmpty()) return this
    return filter { contact ->
        contact.fullName.contains(needle, ignoreCase = true) ||
            contact.designation?.contains(needle, ignoreCase = true) == true ||
            contact.department?.contains(needle, ignoreCase = true) == true
    }
}

/**
 * Recents in reading order: threads with known activity newest first, the
 * never-opened rest behind them in the server's order. Stable, so ties keep
 * their place.
 */
fun sortedRecents(ids: List<String>, newest: Map<String, Long>): List<String> =
    ids.sortedByDescending { newest[it] ?: Long.MIN_VALUE }

/** One row of the Chats listing — a group room or a direct thread. */
sealed interface RecentRow {
    val id: String

    data class Group(val room: GroupRoom) : RecentRow {
        override val id: String get() = room.id
    }

    data class Direct(val contact: CrewContact) : RecentRow {
        override val id: String get() = contact.userId
    }
}

/**
 * Groups and direct threads as one list, newest activity first.
 *
 * Android's All tab is flat — users and rooms interleaved by
 * `sorting_activity` descending (`MembersVM.kt:372-374`), so a room a
 * department stopped using sinks below yesterday's DMs rather than pinning a
 * Groups block on top. Rows without a stamp gather at the end in the order
 * given, the same tolerance [sortedRecents] keeps.
 */
fun recentRows(
    groups: List<GroupRoom>,
    contacts: List<CrewContact>,
    newest: Map<String, Long>,
): List<RecentRow> =
    (groups.map(RecentRow::Group) + contacts.map(RecentRow::Direct))
        .sortedByDescending { it.newestStamp(newest) }

/**
 * A row's ordering stamp: the live map's word when it has one, else the
 * room's own `sorting_activity` — the durable stamp Android sorts rooms by.
 * The live map is backlog-fed and forgets a conversation once its rows are
 * read and aged out; the room row remembers.
 */
fun RecentRow.newestStamp(newest: Map<String, Long>): Long {
    val live = newest[id] ?: Long.MIN_VALUE
    val durable = when (this) {
        is RecentRow.Group -> room.sortingActivity.takeIf { it > 0L } ?: Long.MIN_VALUE
        is RecentRow.Direct -> Long.MIN_VALUE
    }
    return maxOf(live, durable)
}

/**
 * Unread across the conversations the user can still open.
 *
 * Deliberately not the server's `cnc_label` section count: the ledger keeps
 * rows for rooms the user lost (verified live 2026-08-19 — 40 of 45 unread
 * sat in rooms absent from `chat-room`), which the phones clear locally on
 * `notification:silent` instructions and never display. A count is dropped
 * only when the ledger itself filed its key as a room ([ledgerRooms]) and
 * the room list no longer has it — everything else, DMs included, counts.
 */
fun liveChatUnread(
    groups: List<GroupRoom>,
    ledgerRooms: Set<String>,
    unread: Map<String, Int>,
): Int {
    val live = groups.mapTo(mutableSetOf(), GroupRoom::id)
    return unread.entries.sumOf { (id, n) -> if (id in ledgerRooms && id !in live) 0 else n }
}

/**
 * Whether a row has earned a place in the listing at all.
 *
 * Android's All/Members tabs hide rows that have never spoken —
 * `sorting_activity > 0` — with one exception: a department's room shows
 * before its first message (`MembersVM.kt:213-218, 261-263, 287-289`). The
 * Unread and Favourites tabs skip this test, and so does Groups (see
 * [admits] — its Android counterpart lists every room); their own predicate
 * is the whole rule.
 *
 * A direct row passes by construction: it is only ever built from the
 * server's `user:list` — everyone this user has a DM thread with — which is
 * the very fact Android's filter tests against its full-crew list. Re-testing
 * the stamp map here hid real threads (seen live 2026-08-19: a thread with
 * days of history whose read rows had aged out of the backlog). The phones'
 * personal-project clause (every active user listed regardless) is not
 * carried — the desktop opens film productions only.
 */
fun RecentRow.hasStanding(newest: Map<String, Long>): Boolean = when (this) {
    is RecentRow.Group -> newestStamp(newest) > 0L || !room.departmentId.isNullOrEmpty()
    is RecentRow.Direct -> true
}

/**
 * The listing's filter chips, in the web's order — All, Unread, Members,
 * Groups, Favourites (`ChatsComponent`'s chip strip). Declaration order is
 * display order.
 */
enum class ChatFilter(private val labelKey: String) {
    All(S.all),
    Unread(S.unread_txt),
    Members(S.members),
    Groups(S.groups_txt),
    Favourites(S.favorite),
    ;

    val label: String get() = str(labelKey)
}

/**
 * Whether one conversation belongs under this chip — Android's per-tab
 * predicates. All and Members also demand the row has spoken
 * ([hasStanding], `MembersVM.searchOrSubmitUserGroupList`); Unread and
 * Favourites are their own whole rule.
 *
 * Groups deliberately does NOT test standing: Android's Groups tab lists
 * every enabled room the `chat-room` answer returns — `GroupsVM.searchList`
 * (`GroupsVM.kt:168-184`) filters only `enabled` and `is_random_call_group`
 * and sorts by `sorting_activity`, never requiring the room to have spoken.
 * Gating on [hasStanding] here hid every freshly created or never-used room
 * (stamp 0, no department) from the one chip named after them (QA#3).
 */
fun ChatFilter.admits(
    row: RecentRow,
    newest: Map<String, Long>,
    unread: Map<String, Int>,
    favourites: Set<String>,
): Boolean = when (this) {
    ChatFilter.All -> row.hasStanding(newest)
    ChatFilter.Groups -> row is RecentRow.Group
    ChatFilter.Members -> row is RecentRow.Direct && row.hasStanding(newest)
    ChatFilter.Unread -> (unread[row.id] ?: 0) > 0
    ChatFilter.Favourites -> row.id in favourites
}

/** The name a listing row wears — the group's name or the person's. */
fun RecentRow.displayName(): String = when (this) {
    is RecentRow.Group -> room.name
    is RecentRow.Direct -> contact.fullName
}

/**
 * The Chats tab's search box: conversations by display name, case-blind —
 * the same tolerance [searchCrew] extends on the Contacts tab (QA#6).
 */
fun List<RecentRow>.searchRecents(query: String): List<RecentRow> {
    val needle = query.trim()
    if (needle.isEmpty()) return this
    return filter { it.displayName().contains(needle, ignoreCase = true) }
}

/**
 * The C&C rows' date — "Aug 19, 2026" — the web's `OnlydateTimeFormat` as
 * the "Last Entry" line renders it.
 */
fun lastEntryDate(
    atMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    if (atMillis <= 0) return ""
    val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
    return "${MONTHS[at.date.monthNumber - 1]} ${at.date.dayOfMonth}, ${at.date.year}"
}

/** The group rows' stamp — "Aug 19, 2026 at 05:44 PM". */
fun lastMessageAt(
    atMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    if (atMillis <= 0) return ""
    val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
    val hour = ((at.hour + HALF_DAY - 1) % HALF_DAY) + 1
    val half = if (at.hour < HALF_DAY) "AM" else "PM"
    return "${lastEntryDate(atMillis, zone)} at ${hour.pad()}:${at.minute.pad()} $half"
}

/**
 * The listing's time column, the mail list's convention: a clock today, a
 * date this year, month-and-year beyond, nothing for the epoch.
 */
fun chatTimeLabel(
    atMillis: Long,
    nowMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    if (atMillis <= 0) return ""
    val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    val month = MONTHS[at.date.monthNumber - 1]
    return when {
        at.date == today -> "${at.hour.pad()}:${at.minute.pad()}"
        at.date.year == today.year -> "${at.date.dayOfMonth} $month"
        else -> "$month ${at.date.year}"
    }
}

/**
 * The day chip a thread hangs over each day's first message — "Today",
 * "Yesterday", a weekday-and-date this year, date-and-year beyond. Words
 * where words are shorter, because the chip is furniture, not data.
 */
fun chatDayLabel(
    atMillis: Long,
    nowMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    if (atMillis <= 0) return ""
    val day = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone).date
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    val month = MONTHS[day.monthNumber - 1]
    return when {
        day == today -> str(S.today)
        day.toEpochDays() == today.toEpochDays() - 1 -> str(S.yesterday)
        day.year == today.year -> "${WEEKDAYS[day.dayOfWeek.isoDayNumber - 1]} ${day.dayOfMonth} $month"
        else -> "${day.dayOfMonth} $month ${day.year}"
    }
}

private fun Int.pad(): String = toString().padStart(2, '0')

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Noon splits the 12-hour clock; hours past it read PM. */
private const val HALF_DAY = 12

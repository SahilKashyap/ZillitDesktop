@file:Suppress("TooManyFunctions") // One function per rule the web's utils pin with tests.

package com.zillit.desktop.feature.callsheet.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The call sheet's decision rules, transcribed from the web's
 * `callsheetUtils.js`, `callsheetMembers.js` and the `shared/workflow` helpers
 * — every function here is pinned by a web test and re-pinned by this
 * module's own tests. People are matched by id, never by display name, and
 * approval rules read the newest round only.
 */

/** The top-level tabs, in display order. Labels are the web's raw `TABS` constants. */
enum class SheetTab(private val labelKey: String) {
    Drafts(S.cs_drafts),
    Approvals(S.cs_approvals),
    Published(S.desktop_cs_published_call_sheet_tab),
    Permission(S.permission),
    ;

    val label: String get() = str(labelKey)
}

/** The Approvals sections: one flat row Sent | Received | Finalized. */
enum class ApprovalSection(private val labelKey: String) {
    Sent(S.cs_sent),
    Received(S.cs_received),
    Finalized(S.cs_finalized),
    ;

    val label: String get() = str(labelKey)
}

/** The Drafts chips. */
enum class DraftChip(private val labelKey: String) {
    All(S.cs_filter_all),
    Drafts(S.cs_filter_drafts),
    Comments(S.cs_filter_for_comments),
    ;

    val label: String get() = str(labelKey)
}

/** `callSheetToolDisplayName`: posting users see the creation tool's name. */
fun sheetToolName(isPoster: Boolean): String = if (isPoster) str(S.cs_title_creation) else str(S.cs_title_drafts)

/**
 * `resolveCallSheetTabs` + `viewOnlyTabAccess` (`shared/workflow/workflowTabs.js`):
 * posters get every list (and Permission with grid view access). A viewer
 * can author nothing, so a tab is shown only when there is something behind
 * it to DO — Drafts iff on the project's `internal_distribution_receivers`
 * (the people who may post in a thread), Approvals iff on
 * `final_approver_ids`, Published never; named by neither → no tabs at all.
 * Fails OPEN on a FAILED metadata read: both lists ride one GET, and a 500
 * read as "names nobody" would blank the module.
 */
fun sheetTabs(
    isPoster: Boolean,
    canViewGrid: Boolean,
    isFinalApprover: Boolean,
    isInternalReceiver: Boolean,
    metadataUnavailable: Boolean = false,
): List<SheetTab> = when {
    isPoster -> listOfNotNull(
        SheetTab.Drafts,
        SheetTab.Approvals,
        SheetTab.Published,
        SheetTab.Permission.takeIf { canViewGrid },
    )
    metadataUnavailable -> listOf(SheetTab.Drafts, SheetTab.Approvals)
    else -> listOfNotNull(
        SheetTab.Drafts.takeIf { isInternalReceiver },
        SheetTab.Approvals.takeIf { isFinalApprover },
    )
}

/**
 * `isListedMember`: is this user named on a project member-id list
 * (`final_approver_ids`, `internal_distribution_receivers`)? Both sides
 * compared as trimmed strings — the ids come off the metadata GET in either
 * type, and a bare equality hid the Approvals tab from a real approver.
 */
fun isListedMember(ids: List<String>, userId: String?): Boolean {
    val me = userId?.trim().orEmpty()
    return me.isNotEmpty() && ids.any { it.trim() == me }
}

/**
 * `initialLanding`: where a user goes on first load, or null for "stay". A
 * poster needs no move (Drafts is the default); a viewer lands on
 * Approvals → Received when they have it, else on their first tab — always
 * INSIDE the visible set, never a tab the bar does not show.
 */
fun initialLanding(isPoster: Boolean, tabs: List<SheetTab>): SheetTab? = when {
    isPoster -> null
    SheetTab.Approvals in tabs -> SheetTab.Approvals
    else -> tabs.firstOrNull()
}

/**
 * `canPostComments` (`shared/workflow/sendActions.js`): everyone who can see
 * a row opens its thread and reads it; only the row's CREATOR and the
 * project's internal-distribution recipients may write. With no member id,
 * the creator's display NAME is the only handle left.
 */
fun canPostComments(
    row: CallSheetSummary,
    userId: String?,
    userName: String = "",
    isInternalReceiver: Boolean = false,
): Boolean {
    if (isInternalReceiver) return true
    val me = userId?.trim().orEmpty()
    if (me.isNotEmpty()) return row.createdById.trim() == me
    return userName.isNotBlank() && row.createdBy == userName
}

/**
 * `shouldWriteApproverMeta` (ZL-21468): the metadata PUT merges, so an
 * emptied approver list must be WRITTEN, not omitted — but only when the
 * editor OPENED with approvers. A fresh document carrying `[]` from its
 * template must not clear the project default for everyone on first save.
 */
fun shouldWriteApproverMeta(approverIds: List<String>, initialApproverIds: List<String>): Boolean =
    approverIds.isNotEmpty() || initialApproverIds.isNotEmpty()

/** Who sent a reminder, ready to render. */
data class ReminderSender(val name: String, val role: String)

/**
 * `getReminderSender` (`shared/workflow/members.js`): `sent_by` carries the
 * sender's member id (the desktop also reads `sent_by_id`). Found → their
 * CURRENT name and designation, the designation falling back to the recorded
 * role; not found → `sent_by` and `sent_by_role` verbatim, which is also how
 * every reminder written back when the field held a NAME still reads.
 */
fun reminderSender(members: List<SheetMember>, reminder: SheetReminder): ReminderSender {
    val member = members.memberById(reminder.sentById) ?: members.memberById(reminder.sentBy)
        ?: return ReminderSender(reminder.sentBy, reminder.sentByRole)
    return ReminderSender(
        name = member.fullName.ifBlank { reminder.sentBy },
        role = member.designation.ifBlank { reminder.sentByRole },
    )
}

/**
 * `ApprovalsTab` sections: Received for a poster is decided by membership of
 * the project's final approvers ONLY — never by rows or badges, which looped.
 */
fun approvalSections(isPoster: Boolean, isFinalApprover: Boolean): List<ApprovalSection> = when {
    !isPoster -> listOf(ApprovalSection.Received, ApprovalSection.Finalized)
    isFinalApprover -> ApprovalSection.entries
    else -> listOf(ApprovalSection.Sent, ApprovalSection.Finalized)
}

/** Keep the stored section when it is offered, else the first. */
fun resolveSection(sections: List<ApprovalSection>, stored: ApprovalSection?): ApprovalSection =
    stored?.takeIf { it in sections } ?: sections.first()

/**
 * `buildDraftsQuery`: posters see the whole project's pre-signature phase; a
 * view-only user only the sheets they are asked about. Never by creator; no
 * member id for a viewer means no request at all (fail closed).
 */
fun draftsQuery(projectId: String, isPoster: Boolean, memberId: String?): SheetQuery? = when {
    isPoster -> SheetQuery(projectId = projectId, statuses = CallSheetStatus.DRAFT_TAB)
    memberId.isNullOrBlank() -> null
    else -> SheetQuery(projectId = projectId, statuses = CallSheetStatus.DRAFT_TAB, approverId = memberId)
}

/**
 * The Received list's client guard: signature-phase status AND a current-round
 * FINAL request naming me — `approver_id` matches any request, including an
 * INTERNAL (comments) one.
 */
fun receivedRows(rows: List<CallSheetSummary>, userId: String?): List<CallSheetSummary> {
    val me = userId?.trim().orEmpty()
    if (me.isEmpty()) return emptyList()
    return rows.filter { row ->
        row.status in CallSheetStatus.SIGNATURE_PHASE &&
            row.approvals.latestRound("FINAL").any { it.assigneeId.trim() == me }
    }
}

/**
 * `mergeApprovalRequests`: the Sent listing omits requests; fill them in by id
 * from the approver-scoped listing, never overwriting what a row carries.
 */
fun mergeApprovalRequests(rows: List<CallSheetSummary>, approverRows: List<CallSheetSummary>): List<CallSheetSummary> {
    val byId = approverRows.filter { it.approvalsIncluded }.associate { it.id to it.approvals }
    return rows.map { row ->
        val requests = byId[row.id]
        if (row.approvalsIncluded || requests == null) row else row.copy(approvals = requests, approvalsIncluded = true)
    }
}

/**
 * The approver picker's candidates: every accepted member except those
 * already chosen and yourself.
 */
fun approverCandidates(
    members: List<SheetMember>,
    approverIds: List<String>,
    currentUserId: String?,
): List<SheetMember> {
    val chosen = approverIds.map { it.trim() }.toSet()
    val me = currentUserId?.trim().orEmpty()
    return members.filter { member ->
        val id = member.userId.trim()
        id.isNotEmpty() && member.isAccepted && id !in chosen && id != me
    }
}

/**
 * `approverIdsFromSheet`: the sheet's own list — an explicit empty list means
 * nobody signs — or null when the sheet says nothing and the project default
 * decides.
 */
fun approverIdsFromSheet(shared: SharedHeader?): List<String>? =
    shared?.takeIf { it.approverIdsStated }?.approverIds

/**
 * `getReminderAssigneeIds`: the pending approvers of the current round of the
 * stage the sheet is in — INTERNAL while out for comments, else FINAL; with no
 * request for that stage, the latest round across all.
 */
fun reminderAssigneeIds(status: CallSheetStatus, approvals: List<ApprovalRequest>): List<String> {
    if (approvals.isEmpty()) return emptyList()
    val internal = status == CallSheetStatus.PendingInternalApproval
    val pool = approvals.filter { it.isInternal == internal }.ifEmpty { approvals }
    val pending = pool.filter { it.isPending && it.assigneeId.isNotBlank() }
    if (pending.isEmpty()) return emptyList()
    val latest = pending.maxOf { it.round }
    return pending.filter { it.round == latest }.map { it.assigneeId }.distinct()
}

/**
 * `approvalCount`: approved of total in the current round of the stage the
 * status names — FINAL when the sheet has any FINAL request otherwise.
 */
fun approvalCount(status: CallSheetStatus, approvals: List<ApprovalRequest>): Pair<Int, Int> {
    val stageRequests = when (status) {
        CallSheetStatus.PendingInternalApproval -> approvals.filter { it.isInternal }
        CallSheetStatus.PendingApproval -> approvals.filter { it.isFinalStage }
        else -> approvals.filter { it.isFinalStage }.ifEmpty { approvals }
    }
    if (stageRequests.isEmpty()) return 0 to 0
    val latest = stageRequests.maxOf { it.round }
    val round = stageRequests.filter { it.round == latest }
    return round.count { it.isApproved } to round.size
}

/** `approvalStageForStatus`: which stage's status list a row shows. */
fun stageForStatus(status: CallSheetStatus): String =
    if (status == CallSheetStatus.PendingInternalApproval) "INTERNAL" else "FINAL"

/** One line of the "Approval Status" dialog. */
data class ApprovalStatusEntry(
    val id: String,
    val userId: String,
    val name: String,
    val role: String,
    val status: String,
    val atMillis: Long?,
    val reason: String,
    val stage: String,
)

/**
 * `approvalStatusEntries`: the current round of a stage, PENDING first, then
 * APPROVED, then REJECTED, then anything else. Names and roles come from the
 * crew when the person is on it.
 */
fun approvalStatusEntries(
    approvals: List<ApprovalRequest>,
    stage: String,
    members: List<SheetMember>,
): List<ApprovalStatusEntry> =
    approvals.latestRound(stage)
        .sortedBy { request ->
            when {
                request.isPending -> 0
                request.isApproved -> 1
                request.isRejected -> 2
                else -> STATUS_RANK_OTHER
            }
        }
        .map { request ->
            val member = members.memberById(request.assigneeId)
            ApprovalStatusEntry(
                id = request.id,
                userId = request.assigneeId,
                name = member?.fullName?.ifBlank { null }
                    ?: request.assigneeName.ifBlank { request.assigneeId.ifBlank { "-" } },
                role = member?.designation?.ifBlank { null } ?: request.role.ifBlank { str(S.desktop_unknown) },
                status = request.status.ifBlank { "PENDING" }.uppercase(),
                atMillis = request.actedOn ?: request.createdOn,
                reason = request.reason,
                stage = if (request.isInternal) SheetHistory.STAGE_INTERNAL else SheetHistory.STAGE_FINAL,
            )
        }

private const val STATUS_RANK_OTHER = 99

/** `findMemberById` — matched by id only. */
fun List<SheetMember>.memberById(id: String?): SheetMember? {
    val wanted = id?.trim().orEmpty()
    if (wanted.isEmpty()) return null
    return firstOrNull { it.userId.trim() == wanted }
}

/**
 * Received's "my actionable request": the current-round PENDING request that
 * names me, FINAL preferred. A pending INTERNAL-only request is returned so
 * callers can tell "comments only" apart from "nothing".
 */
fun actionableRequest(row: CallSheetSummary, userId: String?): ApprovalRequest? {
    val me = userId?.trim().orEmpty()
    if (me.isEmpty()) return null
    val mine = { request: ApprovalRequest -> request.isPending && request.assigneeId.trim() == me }
    return row.approvals.latestRound("FINAL").firstOrNull(mine)
        ?: row.approvals.latestRound("INTERNAL").firstOrNull(mine)
}

/** Approve / Reject are offered only for a FINAL-stage request. */
fun canApproveReject(row: CallSheetSummary, userId: String?): Boolean =
    actionableRequest(row, userId)?.isFinalStage == true

/**
 * `myPendingFinalRequest` (Sent's creator-approver shortcut): only while the
 * sheet is in the signature phase.
 */
fun pendingFinalRequest(row: CallSheetSummary, userId: String?): ApprovalRequest? {
    if (row.status !in CallSheetStatus.SIGNATURE_PHASE) return null
    return actionableRequest(row, userId)?.takeIf { it.isFinalStage }
}

/** `isInternalOnly`: I hold requests on this sheet and every one is INTERNAL. */
fun isInternalOnly(row: CallSheetSummary, userId: String?): Boolean {
    val me = userId?.trim().orEmpty()
    val mine = row.approvals.filter { it.assigneeId.trim() == me }
    return me.isNotEmpty() && mine.isNotEmpty() && mine.all { it.isInternal }
}

/**
 * `shouldShowReminderBell`: never for the creator; my newest request must be
 * pending and a reminder must reference exactly that request.
 */
fun shouldShowReminderBell(row: CallSheetSummary, userId: String?): Boolean {
    val me = userId?.trim().orEmpty()
    if (me.isEmpty() || row.createdById.trim() == me) return false
    val latest = row.approvals.filter { it.assigneeId.trim() == me }.maxByOrNull { it.round } ?: return false
    if (!latest.isPending) return false
    return row.reminders.any { it.approvalRequestId == latest.id }
}

/** `getLatestReminder`: the newest reminder addressed to my newest pending request. */
fun latestReminder(row: CallSheetSummary, userId: String?): SheetReminder? {
    val me = userId?.trim().orEmpty()
    val request = row.approvals
        .filter { it.assigneeId.trim() == me && it.isPending }
        .maxByOrNull { it.round } ?: return null
    return row.reminders.filter { it.approvalRequestId == request.id }.maxByOrNull { it.createdOn ?: 0L }
}

/** The Finalized publish gate: my own sheet, finally approved. */
fun canPublish(row: CallSheetSummary, userId: String?): Boolean =
    row.status == CallSheetStatus.ApprovedForPublish &&
        userId?.trim().orEmpty().let { it.isNotEmpty() && it == row.createdById.trim() }

/** What a row may send — `sheetSendActions`. */
data class SendActions(val sendForSignature: Boolean, val sendForComments: Boolean, val readComments: Boolean)

/**
 * `sheetSendActions`: one definition for row menus and the editor. A missing
 * or unknown status is a draft; a locked sheet still opens unread comments.
 */
fun sendActions(status: CallSheetStatus, unreadComments: Int = 0): SendActions {
    val isDraft = status == CallSheetStatus.Draft || status == CallSheetStatus.Unknown
    return SendActions(
        sendForSignature = !status.locked,
        sendForComments = !status.locked && isDraft,
        readComments = (!status.locked && !isDraft) || unreadComments > 0,
    )
}

/**
 * ZL-21415: Send for Chat shares a read-only PDF and changes nothing on the
 * sheet, so anyone who can see the row may send it — but not once
 * final-approved or published.
 */
fun sendForChatAllowed(status: CallSheetStatus): Boolean = !status.locked

/** Approvals rows offer Comment unless the sheet is a draft or published (`NO_COMMENT_STATUSES`) — or unread exist. */
fun commentAllowed(status: CallSheetStatus, unread: Int): Boolean =
    (status != CallSheetStatus.Draft && status != CallSheetStatus.Published) || unread > 0

/** `filterDraftsByChip`. */
fun filterDrafts(drafts: List<CallSheetSummary>, chip: DraftChip): List<CallSheetSummary> = when (chip) {
    DraftChip.All -> drafts
    DraftChip.Drafts -> drafts.filter { it.status == CallSheetStatus.Draft }
    DraftChip.Comments -> drafts.filter { it.status.inCommentPhase }
}

/** `getShootDayLabel`: "3 of 50", "3", or "-". */
fun shootDayLabel(shared: SharedHeader?, fallbackTotalDays: String = ""): String {
    val day = shared?.shootDayNumber?.trim().orEmpty()
    val total = shared?.totalDays?.trim().orEmpty().ifEmpty { fallbackTotalDays.trim() }
    return when {
        day.isNotEmpty() && total.isNotEmpty() -> "$day of $total"
        day.isNotEmpty() -> day
        else -> "-"
    }
}

/** The delete confirmation's question — literal on the web. */
fun deleteQuestion(row: CallSheetSummary): String =
    str(S.desktop_cs_delete_question, row.serialNo)

/**
 * `resolveSharedForTemplate`: a SAVED template opens as saved, with metadata
 * filling only blanks and no shoot-day hand-out; a STOCK template gets today,
 * the next shoot day, the project approvers and total days. Returns the header
 * and the shoot day handed out (0 for none).
 */
fun resolveSharedForTemplate(
    shared: SharedHeader,
    meta: SheetMetadata,
    fromSavedTemplate: Boolean,
    todayMs: Long,
): Pair<SharedHeader, Int> {
    if (fromSavedTemplate) {
        return shared.copy(
            dateMs = shared.dateMs?.takeIf { it > 0 } ?: todayMs,
            totalDays = shared.totalDays.ifBlank { meta.totalDays },
            approverIds = shared.approverIds.ifEmpty { meta.finalApproverIds },
            approverIdsStated = true,
            internalReceiverIds = shared.internalReceiverIds.ifEmpty { meta.internalReceiverIds },
        ) to 0
    }
    val shootDay = meta.currentShootDay + 1
    return shared.copy(
        dateMs = todayMs,
        shootDayNumber = shootDay.toString(),
        approverIds = meta.finalApproverIds.ifEmpty { shared.approverIds },
        approverIdsStated = true,
        totalDays = meta.totalDays.ifBlank { shared.totalDays },
    ) to shootDay
}

/**
 * `shouldRegenerateEmployeeRows`: never before the crew has loaded; a stock
 * template always takes today's crew; a saved one only when it carries no
 * crew sections (it would lose its saved In times otherwise).
 */
fun shouldRegenerateEmployeeRows(payload: SheetPayload, memberCount: Int, fromSavedTemplate: Boolean): Boolean = when {
    memberCount <= 0 -> false
    !fromSavedTemplate -> true
    else -> payload.rows.none { row -> row.cells.any { it.renderAs == RenderKind.Employee } }
}

/** `getTemplateSectionTitles`: trimmed titles in render order, de-duplicated ignoring case. */
fun templateSectionTitles(payload: SheetPayload): List<String> {
    val seen = mutableSetOf<String>()
    return payload.rows.flatMap { it.cells }.mapNotNull { cell ->
        cell.title.trim().takeIf { it.isNotEmpty() && seen.add(it.lowercase()) }
    }
}

/**
 * `sanitizeUserIdList`: a users cell keeps only ids of current crew; while the
 * crew is still loading nothing is wiped.
 */
fun sanitizeUserIds(value: String, members: List<SheetMember>): String {
    val tokens = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (members.isEmpty()) return tokens.joinToString(",")
    val ids = members.map { it.userId }.toSet()
    return tokens.filter { it in ids }.joinToString(",")
}

// Time and dates -------------------------------------------------------------

private val EN_GB_MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEPT", "OCT", "NOV", "DEC")

/** `formatDateTime`: `03 SEPT 2026 | 02:05 PM` in the viewer's zone, `-` when missing. */
fun formatDateTime(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (millis == null || millis <= 0L) return "-"
    val time = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    val hour12 = (time.hour % HALF_DAY).let { if (it == 0) HALF_DAY else it }
    val meridiem = if (time.hour < HALF_DAY) "AM" else "PM"
    val date = "${time.day.pad2()} ${EN_GB_MONTHS[time.month.ordinal]} ${time.year}"
    return "$date | ${hour12.pad2()}:${time.minute.pad2()} $meridiem"
}

/** The card's short relative time: `just now`, `5m ago`, `3h ago`, `2d ago`, `4mo ago`, `1y ago`. */
fun relativeShort(millis: Long?, nowMillis: Long): String {
    if (millis == null || millis <= 0L) return "—"
    val seconds = ((nowMillis - millis) / MILLIS_PER_SECOND).coerceAtLeast(0)
    return when {
        seconds < SECONDS_PER_MINUTE -> "just now"
        seconds < SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_MINUTE}m ago"
        seconds < SECONDS_PER_DAY -> "${seconds / SECONDS_PER_HOUR}h ago"
        seconds < SECONDS_PER_DAY * DAYS_PER_MONTH -> "${seconds / SECONDS_PER_DAY}d ago"
        seconds < SECONDS_PER_DAY * DAYS_PER_YEAR -> "${seconds / (SECONDS_PER_DAY * DAYS_PER_MONTH)}mo ago"
        else -> "${seconds / (SECONDS_PER_DAY * DAYS_PER_YEAR)}y ago"
    }
}

/** The Published hero's long relative time: `2 hours ago`, `1 day ago`. */
fun relativeLong(millis: Long?, nowMillis: Long): String {
    if (millis == null || millis <= 0L) return ""
    val seconds = ((nowMillis - millis) / MILLIS_PER_SECOND).coerceAtLeast(0)
    fun unit(count: Long, noun: String) = "$count $noun${if (count == 1L) "" else "s"} ago"
    return when {
        seconds < SECONDS_PER_MINUTE -> "just now"
        seconds < SECONDS_PER_HOUR -> unit(seconds / SECONDS_PER_MINUTE, "minute")
        seconds < SECONDS_PER_DAY -> unit(seconds / SECONDS_PER_HOUR, "hour")
        seconds < SECONDS_PER_DAY * DAYS_PER_MONTH -> unit(seconds / SECONDS_PER_DAY, "day")
        seconds < SECONDS_PER_DAY * DAYS_PER_YEAR -> unit(seconds / (SECONDS_PER_DAY * DAYS_PER_MONTH), "month")
        else -> unit(seconds / (SECONDS_PER_DAY * DAYS_PER_YEAR), "year")
    }
}

private fun Int.pad2(): String = toString().padStart(2, '0')

private const val HALF_DAY = 12
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_DAY = 86_400L
private const val DAYS_PER_MONTH = 30L
private const val DAYS_PER_YEAR = 365L

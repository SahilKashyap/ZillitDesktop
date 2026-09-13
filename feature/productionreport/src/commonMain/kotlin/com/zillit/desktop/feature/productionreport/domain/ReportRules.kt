@file:Suppress("TooManyFunctions") // One function per rule the web's utils pin with tests.

package com.zillit.desktop.feature.productionreport.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The production report's decision rules, transcribed from the web's
 * `productionReportUtils.js` and the `shared/workflow` helpers — every function
 * here is pinned by a web test (`__tests__`) and re-pinned by this module's
 * own tests. People are matched by id, never by display name.
 */

/** Manage sub-tabs, in display order. */
enum class ManageTab(val label: String) { Drafts("Drafts"), Approvals("Approvals"), Published("Published") }

/** The Approvals sections: one flat row Sent | Received | Finalized. */
enum class ApprovalSection(val label: String) { Sent("Sent"), Received("Received"), Finalized("Finalized") }

/** The Drafts chips. */
enum class DraftChip(val label: String) { All("All"), Drafts("Drafts"), Comments("For Comments") }

/** `productionReportToolDisplayName`: posting users see the creation tool's name. */
fun reportToolName(isPoster: Boolean): String = if (isPoster) "Production Report Creation" else "Production Report"

/**
 * `resolveManageTabs` as the shipped app feeds it: posting rights (or admin)
 * open all three; a view-only user the approval flow reaches gets Drafts
 * (read-only) and Approvals; anyone else has the chat alone.
 */
fun manageTabs(isPoster: Boolean, hasApprovalAccess: Boolean): List<ManageTab> = when {
    isPoster -> ManageTab.entries
    hasApprovalAccess -> listOf(ManageTab.Drafts, ManageTab.Approvals)
    else -> emptyList()
}

/**
 * `approvalSectionKeys`: Received for a poster is decided by membership of the
 * project's final approvers ONLY — never by rows or badges, which looped.
 */
fun approvalSections(isPoster: Boolean, isFinalApprover: Boolean): List<ApprovalSection> = when {
    !isPoster -> listOf(ApprovalSection.Received, ApprovalSection.Finalized)
    isFinalApprover -> ApprovalSection.entries
    else -> listOf(ApprovalSection.Sent, ApprovalSection.Finalized)
}

/** `resolveActiveSection`: keep the stored section when it is offered, else the first. */
fun resolveSection(sections: List<ApprovalSection>, stored: ApprovalSection?): ApprovalSection =
    stored?.takeIf { it in sections } ?: sections.first()

/**
 * `visibleBadgeCount` (ZL-20659): a count on a tab known to be empty is
 * hidden; an unfetched tab trusts the backend count.
 */
fun visibleBadgeCount(count: Int, rowCount: Int, loaded: Boolean): Int = when {
    count <= 0 -> 0
    !loaded -> count
    rowCount > 0 -> count
    else -> 0
}

/**
 * `hasApprovalInvolvement`: listed as a default approver or comment receiver,
 * or holding a pending current-round request. [assignedReportCount] null is
 * "still asking" — not involved yet, so tabs reveal additively. A probe that
 * failed fails OPEN.
 */
fun hasApprovalInvolvement(
    userId: String?,
    assignedReportCount: Int?,
    probeFailed: Boolean,
    defaultApproverIds: List<String>,
    internalReceiverIds: List<String>,
): Boolean {
    val me = userId?.trim().orEmpty()
    val named = me in defaultApproverIds.map { it.trim() } || me in internalReceiverIds.map { it.trim() }
    if (me.isNotEmpty() && named) {
        return true
    }
    if (assignedReportCount != null && assignedReportCount > 0) return true
    return probeFailed
}

/**
 * `countApprovalAssignments`: reports where [userId] holds a CURRENT-round
 * request that is PENDING (a missing status reads as pending), at either stage.
 */
fun countApprovalAssignments(reports: List<ReportSummary>, userId: String?): Int {
    val me = userId?.trim().orEmpty()
    if (me.isEmpty()) return 0
    return reports.count { report ->
        listOf("FINAL", "INTERNAL").any { stage ->
            report.approvals.latestRound(stage)
                .any { it.assigneeId.trim() == me && (it.isPending || it.status.isBlank()) }
        }
    }
}

/**
 * The Received list's client guard: signature-phase status AND a current-round
 * FINAL request naming me — `approver_id` matches any request, including an
 * INTERNAL-only one, and a removed approver's row must not linger.
 */
fun receivedRows(rows: List<ReportSummary>, userId: String?): List<ReportSummary> {
    val me = userId?.trim().orEmpty()
    if (me.isEmpty()) return emptyList()
    return rows.filter { row ->
        row.status in ReportStatus.SIGNATURE_PHASE && row.approvals.latestRound("FINAL")
            .any { it.assigneeId.trim() == me }
    }
}

/**
 * `filterApproverCandidates` (Sep 2026): every accepted member except those
 * already chosen and yourself. The posting-rights gate it replaced is gone.
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
 * `approverIdsFromReport`: the payload's own list, else the first "Approvers"
 * section's first column, else the (empty) list the payload states — and null
 * when there is no payload to ask.
 */
fun approverIdsFromReport(detail: ReportDetail?): List<String>? {
    if (detail == null || !detail.hasPayload) return null
    val own = detail.payload.shared.approverIds
    if (own.isNotEmpty()) return own
    val fromBlock = detail.payload.rows.asSequence()
        .flatMap { it.cells.asSequence() }
        .filter { it.title.trim().equals("approvers", ignoreCase = true) }
        .map { cell -> cell.rows.mapNotNull { it.values.firstOrNull()?.value?.trim() }.filter { it.isNotEmpty() } }
        .firstOrNull { it.isNotEmpty() }
    return fromBlock ?: own
}

/** `resolveApproverIdsForSend`: the report's own answer, else the project default. */
fun resolveApproverIdsForSend(detail: ReportDetail?, metaApproverIds: List<String>): List<String> =
    approverIdsFromReport(detail) ?: metaApproverIds

/**
 * `getReminderAssigneeIds` (ZL-20678): the pending approvers of the current
 * round of the stage the report is in — INTERNAL while out for comments, else
 * FINAL; with no request for that stage, the latest round across all.
 */
fun reminderAssigneeIds(status: ReportStatus, approvals: List<ApprovalRequest>): List<String> {
    if (approvals.isEmpty()) return emptyList()
    val internal = status == ReportStatus.PendingInternalApproval
    val stageRequests = approvals.filter { it.isInternal == internal }
    val pool = stageRequests.ifEmpty { approvals }
    val latest = pool.maxOf { it.round }
    return pool.filter { it.round == latest && it.isPending && it.assigneeId.isNotBlank() }
        .map { it.assigneeId }
        .distinct()
}

/**
 * `approvalCountFor`: approved of total in the current round of the stage the
 * status names — FINAL when the report has any FINAL request otherwise.
 */
fun approvalCount(status: ReportStatus, approvals: List<ApprovalRequest>): Pair<Int, Int> {
    val stageRequests = when (status) {
        ReportStatus.PendingInternalApproval -> approvals.filter { it.isInternal }
        ReportStatus.PendingApproval -> approvals.filter { it.isFinalStage }
        else -> approvals.filter { it.isFinalStage }.ifEmpty { approvals }
    }
    if (stageRequests.isEmpty()) return 0 to 0
    val latest = stageRequests.maxOf { it.round }
    val round = stageRequests.filter { it.round == latest }
    return round.count { it.isApproved } to round.size
}

/** `approvalStageForStatus`: which stage's popover a row shows. */
fun stageForStatus(status: ReportStatus): String =
    if (status == ReportStatus.PendingInternalApproval) "INTERNAL" else "FINAL"

/** One line of the "Approval Status" popover. */
data class ApprovalStatusEntry(
    val id: String,
    val userId: String,
    val name: String,
    val role: String,
    val status: String,
    val actedOn: Long?,
    val reason: String,
    val stage: String,
)

/**
 * `approvalStatusEntries`: the current round of a stage, PENDING first, then
 * APPROVED, then REJECTED, then anything else — a missing status is shown as
 * PENDING and sorted with it (the web sorted it last while labelling it pending).
 */
fun approvalStatusEntries(approvals: List<ApprovalRequest>, stage: String): List<ApprovalStatusEntry> =
    approvals.latestRound(stage)
        .sortedBy { request ->
            when {
                request.isPending || request.status.isBlank() -> 0
                request.isApproved -> 1
                request.isRejected -> 2
                else -> STATUS_RANK_OTHER
            }
        }
        .map { request ->
            ApprovalStatusEntry(
                id = request.id,
                userId = request.assigneeId,
                name = request.assigneeName.ifBlank { request.assigneeId.ifBlank { "-" } },
                role = request.role.ifBlank { "Unknown" },
                status = request.status.ifBlank { "PENDING" }.uppercase(),
                actedOn = request.actedOn ?: request.createdOn,
                reason = request.reason,
                stage = if (request.isInternal) "Internal" else "Final",
            )
        }

private const val STATUS_RANK_OTHER = 99

/** Everyone a row names as an approver: its requests, then its payload list. */
fun approverIdsOf(row: ReportSummary): List<String> =
    (row.approvals.map { it.assigneeId } + row.shared?.approverIds.orEmpty())
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/**
 * Received's "my actionable request": the current-round PENDING request that
 * names me, FINAL preferred (the web matched by name and ignored rounds).
 */
fun actionableRequest(row: ReportSummary, userId: String?): ApprovalRequest? {
    val me = userId?.trim().orEmpty()
    if (me.isEmpty()) return null
    val mine = { request: ApprovalRequest -> request.isPending && request.assigneeId.trim() == me }
    return row.approvals.latestRound("FINAL").firstOrNull(mine)
        ?: row.approvals.latestRound("INTERNAL").firstOrNull(mine)
}

/** Approve / Reject are offered only for a FINAL-stage request. */
fun canApproveReject(row: ReportSummary, userId: String?): Boolean =
    actionableRequest(row, userId)?.isFinalStage == true

/** `isInternalOnly`: I hold requests on this report and every one is INTERNAL. */
fun isInternalOnly(row: ReportSummary, userId: String?): Boolean {
    val me = userId?.trim().orEmpty()
    val mine = row.approvals.filter { it.assigneeId.trim() == me }
    return me.isNotEmpty() && mine.isNotEmpty() && mine.all { it.isInternal }
}

/**
 * `shouldShowReminderBell`: never for the creator; my newest request must be
 * pending and a reminder must reference exactly that request.
 */
fun shouldShowReminderBell(row: ReportSummary, userId: String?): Boolean {
    val me = userId?.trim().orEmpty()
    if (me.isEmpty() || row.createdById.trim() == me) return false
    val latest = row.approvals.filter { it.assigneeId.trim() == me }.maxByOrNull { it.round } ?: return false
    if (!latest.isPending) return false
    return row.reminders.any { it.approvalRequestId == latest.id }
}

/** `getUserReminders` (ZL-20660): every reminder addressed to any of my requests, newest first. */
fun userReminders(row: ReportSummary, userId: String?): List<ReportReminder> {
    val me = userId?.trim().orEmpty()
    val myRequests = row.approvals.filter { it.assigneeId.trim() == me }.map { it.id }.toSet()
    return row.reminders.filter { it.approvalRequestId in myRequests }.sortedByDescending { it.createdOn ?: 0L }
}

/** The Finalized publish gate: posting rights, my own report, and final approval. */
fun canPublish(row: ReportSummary, isPoster: Boolean, userId: String?): Boolean =
    isPoster && row.status == ReportStatus.ApprovedForPublish &&
        userId?.trim().orEmpty().let { it.isNotEmpty() && it == row.createdById.trim() }

/** What a row may send — `sheetSendActions`. */
data class SendActions(val sendForSignature: Boolean, val sendForComments: Boolean, val readComments: Boolean)

/**
 * `sheetSendActions`: one definition for row menus and the editor. A missing
 * or unknown status is a draft; a locked report still opens unread comments.
 */
fun sendActions(status: ReportStatus, unreadComments: Int = 0): SendActions {
    val isDraft = status == ReportStatus.Draft || status == ReportStatus.Unknown
    return SendActions(
        sendForSignature = !status.locked,
        sendForComments = !status.locked && isDraft,
        readComments = (!status.locked && !isDraft) || unreadComments > 0,
    )
}

/** `filterDraftsByChip`. */
fun filterDrafts(drafts: List<ReportSummary>, chip: DraftChip): List<ReportSummary> = when (chip) {
    DraftChip.All -> drafts
    DraftChip.Drafts -> drafts.filter { it.status == ReportStatus.Draft }
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

/** The delete confirmation's question, with the day when the row knows it. */
fun deleteQuestion(row: ReportSummary): String {
    val name = row.name.trim()
    val day = shootDayLabel(row.shared)
    val subject = if (name.isNotEmpty()) "\"$name\"" else "this production report"
    val suffix = if (day != "-") " (Day $day)" else ""
    return "Are you sure you want to delete $subject$suffix?"
}

/**
 * `resolveSharedForTemplate`: a SAVED template opens as saved, with metadata
 * filling only blanks and no shoot-day hand-out; a STOCK template gets today,
 * the next shoot day, the project approvers and total days.
 */
fun resolveSharedForTemplate(
    shared: SharedHeader,
    meta: SheetMetadata,
    fromSavedTemplate: Boolean,
    todayYmd: String,
): Pair<SharedHeader, Int> {
    if (fromSavedTemplate) {
        return shared.copy(
            dateYmd = shared.dateYmd.ifBlank { todayYmd },
            totalDays = shared.totalDays.ifBlank { meta.totalDays },
            approverIds = shared.approverIds.ifEmpty { meta.finalApproverIds },
            internalReceiverIds = shared.internalReceiverIds.ifEmpty { meta.internalReceiverIds },
        ) to 0
    }
    val shootDay = meta.currentShootDay + 1
    return shared.copy(
        dateYmd = todayYmd,
        shootDayNumber = shootDay.toString(),
        approverIds = meta.finalApproverIds.ifEmpty { shared.approverIds },
        totalDays = meta.totalDays.ifBlank { shared.totalDays },
    ) to shootDay
}

/**
 * `shouldRegenerateEmployeeRows`: a stock template always takes today's crew;
 * a saved one only when it carries no crew sections — regenerated rows are
 * blank, so saved values could only be kept, never merged.
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

// Time and dates -------------------------------------------------------------

private val EN_GB_MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEPT", "OCT", "NOV", "DEC")

/** `formatDateTime`: `03 SEPT 2026 | 02:05 PM` in the viewer's zone, `-` when missing. */
fun formatDateTime(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (millis == null || millis <= 0L) return "-"
    val time = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    val hour12 = (time.hour % HALF_DAY).let { if (it == 0) HALF_DAY else it }
    val meridiem = if (time.hour < HALF_DAY) "AM" else "PM"
    val date = "${time.dayOfMonth.pad2()} ${EN_GB_MONTHS[time.monthNumber - 1]} ${time.year}"
    return "$date | ${hour12.pad2()}:${time.minute.pad2()} $meridiem"
}

/** The date half of [formatDateTime], or `-`. */
fun formatDateOnly(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String =
    formatDateTime(millis, zone).substringBefore(" | ")

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

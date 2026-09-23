package com.zillit.desktop.feature.productionreport.domain

import kotlin.math.abs

/** One line of the History dialog. */
data class HistoryEntry(
    val id: String,
    /** `Internal`, `Final`, or empty. */
    val stage: String,
    val action: String,
    val by: String,
    /** A designation key or label; translated on render when possible. */
    val role: String,
    val atMillis: Long?,
    val reason: String = "",
    val revisionText: String = "",
    val message: String = "",
    /** Reminder batches: everyone the one message went to. */
    val recipients: List<String> = emptyList(),
) {
    /** `{stage} {action} on {time} • v{n}` — the dialog's meta line. */
    fun metaLine(formatTime: (Long?) -> String): String = buildString {
        if (stage.isNotEmpty()) append(stage).append(' ')
        append(action.ifEmpty { "Action" })
        append(" on ").append(formatTime(atMillis))
        if (revisionText.isNotEmpty()) append(" • ").append(revisionText)
    }
}

/**
 * `approvalHistoryEntries`: decisions, sends, reminders (one entry per batch —
 * the same message within five seconds of the batch's first), the create and
 * every later revision, newest first, at most [max]. Needs the report DETAIL:
 * list rows lack revisions and reminders.
 */
object ReportHistory {

    private const val REMINDER_BATCH_WINDOW_MS = 5_000L
    private const val DEFAULT_MAX = 50

    fun entries(
        detail: ReportDetail,
        members: List<SheetMember> = emptyList(),
        max: Int = DEFAULT_MAX,
    ): List<HistoryEntry> {
        val versionById = detail.revisions.associate { it.id to it.version }
        val all = decisions(detail.approvals, versionById) +
            sends(detail) +
            reminders(detail.reminders, members) +
            revisions(detail)
        return all.sortedByDescending { it.atMillis ?: 0L }.take(max)
    }

    private fun decisions(approvals: List<ApprovalRequest>, versionById: Map<String, Int>): List<HistoryEntry> =
        approvals.filter { it.isApproved || it.isRejected }.map { request ->
            val version = request.revisionVersion ?: versionById[request.revisionId]
            HistoryEntry(
                id = request.id,
                stage = if (request.isInternal) "Internal" else "Final",
                action = if (request.isApproved) "Approved" else "Rejected",
                by = request.assigneeName.ifBlank { request.assigneeId.ifBlank { "-" } },
                role = request.role.ifBlank { "Unknown" },
                atMillis = request.actedOn ?: request.createdOn,
                reason = request.reason,
                revisionText = version?.let { "v$it" }.orEmpty(),
            )
        }

    /** One "Sent" entry per stage and round, dated by the first request of that round. */
    private fun sends(detail: ReportDetail): List<HistoryEntry> =
        detail.approvals
            .groupBy { (if (it.isInternal) "INTERNAL" else "FINAL") to it.round }
            .map { (key, requests) ->
                val first = requests.first()
                HistoryEntry(
                    id = "sent_${key.first}_${key.second}",
                    stage = if (first.isInternal) "Internal" else "Final",
                    action = if (first.isInternal) "Sent for Comments" else "Sent for Signature",
                    by = detail.summary.createdBy.ifBlank { "-" },
                    role = "",
                    atMillis = first.createdOn,
                )
            }

    /** One entry per batch; the sender resolved by member id (`getReminderSender`), verbatim when unknown. */
    private fun reminders(reminders: List<ReportReminder>, members: List<SheetMember>): List<HistoryEntry> {
        val groups = mutableListOf<MutableList<ReportReminder>>()
        reminders.sortedBy { it.createdOn ?: 0L }.forEach { reminder ->
            val at = reminder.createdOn ?: 0L
            val batch = groups.firstOrNull { group ->
                val head = group.first()
                head.message == reminder.message && abs((head.createdOn ?: 0L) - at) < REMINDER_BATCH_WINDOW_MS
            }
            if (batch != null) batch += reminder else groups += mutableListOf(reminder)
        }
        return groups.map { group ->
            val head = group.first()
            val sender = reminderSender(members, head)
            HistoryEntry(
                id = head.id,
                stage = "",
                action = "Reminder Sent",
                by = sender.name.ifBlank { "-" },
                role = sender.role.ifBlank { "Unknown" },
                atMillis = head.createdOn,
                message = head.message,
                recipients = group.map { it.assigneeName.ifBlank { "assignee" } },
            )
        }
    }

    private fun revisions(detail: ReportDetail): List<HistoryEntry> {
        val sorted = detail.revisions.sortedBy { it.version }
        val first = sorted.firstOrNull() ?: return emptyList()
        val created = HistoryEntry(
            id = "created_${detail.summary.id}",
            stage = "",
            action = "Created",
            by = first.createdBy.ifBlank { detail.summary.createdBy.ifBlank { "-" } },
            role = "",
            atMillis = first.createdOn ?: detail.summary.createdOn,
            revisionText = if (first.version > 0) "v${first.version}" else "",
        )
        val updates = sorted.filter { it.version > 1 }.map { revision ->
            HistoryEntry(
                id = "updated_${revision.id}",
                stage = "",
                action = "Updated",
                by = revision.createdBy.ifBlank { detail.summary.createdBy.ifBlank { "-" } },
                role = "",
                atMillis = revision.createdOn,
                revisionText = "v${revision.version}",
                message = revision.notes.trim(),
            )
        }
        return listOf(created) + updates
    }
}

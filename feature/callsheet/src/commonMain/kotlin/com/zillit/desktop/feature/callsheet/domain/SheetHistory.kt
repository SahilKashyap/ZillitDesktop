package com.zillit.desktop.feature.callsheet.domain

import kotlin.math.abs

/** One line of the History timeline. */
data class HistoryEntry(
    val id: String,
    /** Whose face the line shows. */
    val userId: String,
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
)

/**
 * `approvalHistoryEntries`: decisions, sends, reminders (one entry per batch —
 * the same message within five seconds of the batch's first), the create and
 * every later revision, newest first, at most [max]. Needs the sheet DETAIL:
 * list rows lack revisions and reminders. Names come from the crew when the
 * person is on it.
 */
object SheetHistory {

    private const val REMINDER_BATCH_WINDOW_MS = 5_000L
    private const val DEFAULT_MAX = 50

    fun entries(detail: CallSheetDetail, members: List<SheetMember>, max: Int = DEFAULT_MAX): List<HistoryEntry> {
        val versionById = detail.revisions.associate { it.id to it.version }
        val all = decisions(detail.approvals, versionById, members) +
            sends(detail, members) +
            reminders(detail.reminders, members) +
            revisions(detail, members)
        return all.sortedByDescending { it.atMillis ?: 0L }.take(max)
    }

    private fun name(members: List<SheetMember>, id: String, fallback: String): String =
        members.memberById(id)?.fullName?.ifBlank { null } ?: fallback.ifBlank { "-" }

    private fun role(members: List<SheetMember>, id: String, fallback: String): String =
        members.memberById(id)?.designation?.ifBlank { null } ?: fallback

    private fun decisions(
        approvals: List<ApprovalRequest>,
        versionById: Map<String, Int>,
        members: List<SheetMember>,
    ): List<HistoryEntry> =
        approvals.filter { it.isApproved || it.isRejected }.map { request ->
            val version = request.revisionVersion ?: versionById[request.revisionId]
            HistoryEntry(
                id = request.id,
                userId = request.assigneeId,
                stage = if (request.isInternal) "Internal" else "Final",
                action = if (request.isApproved) "Approved" else "Rejected",
                by = name(members, request.assigneeId, request.assigneeName.ifBlank { request.assigneeId }),
                role = role(members, request.assigneeId, request.role.ifBlank { "Unknown" }),
                atMillis = request.actedOn ?: request.createdOn,
                reason = request.reason,
                revisionText = version?.takeIf { it > 0 }?.let { "v$it" }.orEmpty(),
            )
        }

    /** One "Sent" entry per stage and round, dated by the first request of that round. */
    private fun sends(detail: CallSheetDetail, members: List<SheetMember>): List<HistoryEntry> =
        detail.approvals
            .groupBy { (if (it.isInternal) "INTERNAL" else "FINAL") to it.round }
            .map { (key, requests) ->
                val first = requests.first()
                HistoryEntry(
                    id = "sent_${key.first}_${key.second}",
                    userId = detail.summary.createdById,
                    stage = if (first.isInternal) "Internal" else "Final",
                    action = if (first.isInternal) "Sent for Comments" else "Sent for Signature",
                    by = name(members, detail.summary.createdById, detail.summary.createdBy),
                    role = role(members, detail.summary.createdById, ""),
                    atMillis = first.createdOn,
                )
            }

    private fun reminders(reminders: List<SheetReminder>, members: List<SheetMember>): List<HistoryEntry> {
        val groups = mutableListOf<MutableList<SheetReminder>>()
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
            HistoryEntry(
                id = head.id,
                userId = head.sentById,
                stage = "",
                action = "Reminder Sent",
                by = name(members, head.sentById, head.sentBy),
                role = role(members, head.sentById, head.sentByRole.ifBlank { "Unknown" }),
                atMillis = head.createdOn,
                message = head.message,
            )
        }
    }

    private fun revisions(detail: CallSheetDetail, members: List<SheetMember>): List<HistoryEntry> {
        val sorted = detail.revisions.sortedBy { it.version }
        val first = sorted.firstOrNull() ?: return emptyList()
        val creatorId = first.createdById.ifBlank { detail.summary.createdById }
        val created = HistoryEntry(
            id = "created_${detail.summary.id}",
            userId = creatorId,
            stage = "",
            action = "Created",
            by = name(members, creatorId, first.createdBy.ifBlank { detail.summary.createdBy }),
            role = role(members, creatorId, ""),
            atMillis = first.createdOn ?: detail.summary.createdOn,
            revisionText = if (first.version > 0) "v${first.version}" else "",
        )
        val updates = sorted.filter { it.version > 1 }.map { revision ->
            HistoryEntry(
                id = "updated_${revision.id}",
                userId = revision.createdById,
                stage = "",
                action = "Updated",
                by = name(members, revision.createdById, revision.createdBy.ifBlank { detail.summary.createdBy }),
                role = role(members, revision.createdById, ""),
                atMillis = revision.createdOn,
                revisionText = "v${revision.version}",
                message = revision.notes.trim(),
            )
        }
        return listOf(created) + updates
    }
}

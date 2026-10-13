package com.zillit.desktop.feature.home.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Everyone on one event, searchable — the web's `InviteeListModal`.
 *
 * Opened from the detail popover's invitee card. Two sections, as the web
 * draws them: the production's own crew with where each of them stands, and
 * below it the guests invited by address, who have no status to show because
 * nobody asked them through Zillit.
 */
@Composable
internal fun InviteeListDialog(
    list: InviteeListState?,
    onEvent: (CalendarEvent2Event) -> Unit,
) {
    // The list survives its own exit animation: the state drops on dismiss,
    // but the rows must not blank while the card fades out.
    val shown = remember { mutableStateOf<InviteeListState?>(null) }
    if (list != null) shown.value = list
    val current = shown.value ?: return

    ZillitDialogShell(
        title = current.eventTitle.takeIf { it.isNotBlank() } ?: str(S.invitees),
        icon = ZillitIcons.Users,
        onDismiss = { onEvent(CalendarEvent2Event.HideInvitees) },
        visible = list != null,
        width = DIALOG_WIDTH,
    ) {
        ZillitSearchField(
            value = current.search,
            onValueChange = { onEvent(CalendarEvent2Event.SearchInvitees(it)) },
            placeholder = str(S.desktop_cal_invitee_search),
            modifier = Modifier.fillMaxWidth(),
        )

        val rows = current.visibleRows()
        when {
            current.isLoading -> repeat(SKELETON_ROWS) { ZillitSkeletonBar(height = SKELETON_ROW) }

            current.error != null -> ZillitText(
                text = current.error,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.danger,
            )

            rows.isEmpty() -> ZillitEmptyState(
                title = if (current.search.isBlank()) {
                    str(S.no_invitees)
                } else {
                    str(S.desktop_cal_no_invitees_match)
                },
                icon = ZillitIcons.Users,
            )

            else -> {
                InviteeSection(
                    heading = str(S.members),
                    rows = rows.filterNot { it.isExternal },
                    list = current,
                    withStatus = true,
                )
                InviteeSection(
                    heading = str(S.external_guests),
                    rows = rows.filter { it.isExternal },
                    list = current,
                    withStatus = false,
                )
            }
        }
    }
}

/**
 * One titled group of rows, with its count beside the heading.
 *
 * Absent rather than empty when nobody is in it: an "External Guests (0)"
 * heading on the common case is noise on every event that has none.
 */
@Composable
private fun InviteeSection(
    heading: String,
    rows: List<EventInviteeRow>,
    list: InviteeListState,
    withStatus: Boolean,
) {
    if (rows.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = heading,
                style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = "${rows.size}",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }

        if (withStatus) StatusSummary(rows)

        rows.forEachIndexed { index, row ->
            InviteeRow(row, list, withStatus)
            if (index < rows.lastIndex) ZillitDivider()
        }
    }
}

/** "4 Accepted · 2 Pending · 1 Declined" as pills, counting only what there is. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusSummary(rows: List<EventInviteeRow>) {
    val counts = listOf(
        Triple(InvitationStatus.Accepted, StatusTone.Ready, S.desktop_cal_n_accepted),
        Triple(InvitationStatus.Pending, StatusTone.Pending, S.desktop_inv_n_pending),
        Triple(InvitationStatus.Rejected, StatusTone.Rejected, S.desktop_cal_n_declined),
    ).mapNotNull { (status, tone, key) ->
        rows.count { it.status == status }.takeIf { it > 0 }?.let { Triple(it, tone, key) }
    }
    if (counts.isEmpty()) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        counts.forEach { (count, tone, key) ->
            ZillitStatusPill(label = str(key, count), tone = tone)
        }
    }
}

/** One person: their face, their name and job, and their answer. */
@Composable
private fun InviteeRow(row: EventInviteeRow, list: InviteeListState, withStatus: Boolean) {
    val colors = ZillitTheme.colors
    val name = list.nameOf(row)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = name, userId = row.userId, size = ROW_AVATAR)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            ZillitText(text = name, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
            list.jobLine(row)?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            // Why they said no, in their own words — the organiser reads this
            // to decide whether to move the event.
            row.rejectionReason
                ?.takeIf { it.isNotBlank() && row.status == InvitationStatus.Rejected }
                ?.let {
                ZillitText(
                    text = "“$it”",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.warning,
                )
            }
        }

        if (withStatus) {
            ZillitStatusPill(
                label = str(row.status.answerLabel),
                tone = row.status.tone,
                dot = true,
            )
        }
    }
}

/**
 * What each answer is called here — the web's `STATUS_LABELS`.
 *
 * "Declined", not the tab strip's "Rejected": the summary chips above the
 * rows say "1 Declined", and one word for the status in two places on the
 * same list reads as two different states. `declined_events` is the
 * calendar's own key for exactly this word, already translated.
 */
internal val InvitationStatus.answerLabel: String
    get() = when (this) {
        InvitationStatus.Rejected -> S.declined_events
        else -> label
    }

/** The hue each answer is drawn in — the web's `STATUS_COLORS`, by feeling. */
internal val InvitationStatus.tone: StatusTone
    get() = when (this) {
        InvitationStatus.Accepted -> StatusTone.Ready
        InvitationStatus.Pending -> StatusTone.Pending
        InvitationStatus.Rejected -> StatusTone.Rejected
        InvitationStatus.Expired -> StatusTone.Neutral
    }

/**
 * The rows the search leaves, answered first and unanswered last.
 *
 * Sorted by answer rather than by name, as the web sorts it: the question this
 * list is opened to settle is "who is actually coming", and that is the top of
 * it. Searching covers the name, the address, the job and the department —
 * "who from Camera is coming" is the question a coordinator really has.
 */
internal fun InviteeListState.visibleRows(): List<EventInviteeRow> {
    val query = search.trim().lowercase()
    return rows
        .filter { row ->
            query.isEmpty() || listOfNotNull(
                nameOf(row),
                row.email,
                crewFor(row)?.designation,
                crewFor(row)?.department,
            ).any { it.lowercase().contains(query) }
        }
        .sortedBy { it.status.sortOrder }
}

/** The crew record behind a row, when the production still has one. */
internal fun InviteeListState.crewFor(row: EventInviteeRow): EventInvitee? =
    row.userId?.let { id -> directory.firstOrNull { it.userId == id } }

/**
 * What to call them: their crew name, else the address they were invited at,
 * else their bare id — which is the honest answer for somebody since removed
 * from the production, and better than a blank row.
 */
internal fun InviteeListState.nameOf(row: EventInviteeRow): String =
    crewFor(row)?.name
        ?: row.email
        ?: row.userId
        ?: row.id

/** "Camera Operator · Camera" — whichever halves the crew record has. */
internal fun InviteeListState.jobLine(row: EventInviteeRow): String? {
    val crew = crewFor(row) ?: return null
    return listOfNotNull(
        crew.designation?.takeIf { it.isNotBlank() },
        crew.department?.takeIf { it.isNotBlank() },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The web's `STATUS_SORT_ORDER`: answered yes, then waiting, then no. */
private val InvitationStatus.sortOrder: Int
    get() = when (this) {
        InvitationStatus.Accepted -> 0
        InvitationStatus.Pending -> 1
        InvitationStatus.Rejected -> 2
        InvitationStatus.Expired -> 3
    }

private val DIALOG_WIDTH = 480.dp
private val ROW_AVATAR = 32.dp
private val SKELETON_ROW = 44.dp
private const val SKELETON_ROWS = 4

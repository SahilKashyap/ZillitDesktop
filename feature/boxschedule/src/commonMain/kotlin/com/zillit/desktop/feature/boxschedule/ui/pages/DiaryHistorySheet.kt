package com.zillit.desktop.feature.boxschedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.boxschedule.domain.AudienceMode
import com.zillit.desktop.feature.boxschedule.domain.DiaryFormat
import com.zillit.desktop.feature.boxschedule.domain.DiaryHistory
import com.zillit.desktop.feature.boxschedule.domain.HistoryAction
import com.zillit.desktop.feature.boxschedule.domain.HistoryEntry
import com.zillit.desktop.feature.boxschedule.domain.HistorySnapshot
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.HistoryPanel
import com.zillit.desktop.feature.boxschedule.ui.PanelEvent
import kotlinx.datetime.TimeZone

/** "HISTORY" — `ActivityLogDrawer`: everything that changed, newest first, with its details. */
@Composable
internal fun HistorySheet(state: BoxScheduleUiState, panel: HistoryPanel, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val visible = remember(panel.entries, panel.action, panel.day, state.zone) {
        DiaryHistory.visible(panel.entries, panel.action, panel.day, state.zone)
    }
    DiarySheet(
        title = "HISTORY",
        subtitle = {
            ZillitText(
                "Everything that changed in the Production Diary/Box Schedule",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        },
        onDismiss = { onEvent(PanelEvent.CloseHistory) },
        width = 480.dp,
        padded = false,
    ) {
        HistoryFilters(state, panel, onEvent)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                panel.loading -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) { ZillitSpinner() }
                visible.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ZillitText(
                        if (panel.entries.isEmpty()) "No history yet" else "No matching changes",
                        style = ZillitTheme.typography.titleSmall,
                        color = colors.textSecondary,
                    )
                    ZillitText(
                        if (panel.entries.isEmpty()) {
                            "Changes to schedules, events, and notes will appear here automatically."
                        } else {
                            "Try a different filter."
                        },
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                else -> visible.forEach { entry -> HistoryCard(state, panel, entry, onEvent) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryFilters(state: BoxScheduleUiState, panel: HistoryPanel, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val options: List<HistoryAction?> = listOf(null) + HistoryAction.entries
            DiaryDropdown(
                selected = panel.action ?: options.first(),
                options = options,
                onSelect = { onEvent(PanelEvent.FilterHistory(it)) },
                label = { it?.filterLabel ?: "All Actions" },
                // All Actions is the null choice, which the dropdown draws as its placeholder.
                placeholder = "All Actions",
                modifier = Modifier.weight(1f),
                menuWidth = 200.dp,
            )
            DiaryDateField(
                value = panel.day,
                onPick = { onEvent(PanelEvent.HistoryDay(it)) },
                today = state.today,
                placeholder = "Pick a date",
                clearable = true,
                modifier = Modifier.weight(1f),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HistoryAction.entries.forEach { action ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Dot(hexColor(action.color) ?: colors.textMuted, size = 8.dp)
                    ZillitText(
                        action.label,
                        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun HistoryCard(
    state: BoxScheduleUiState,
    panel: HistoryPanel,
    entry: HistoryEntry,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val tone = hexColor(entry.known?.color) ?: colors.textMuted
    val revision = DiaryHistory.revisionFor(entry, panel.revisions)
    val (name, designation) = performer(entry, state)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(tone, size = 8.dp)
            ZillitText(entry.actionLabel, style = ZillitTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp,
            ), color = tone)
            if (entry.targetLabel.isNotBlank()) {
                ZillitText("·", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
                Caption(entry.targetLabel, color = colors.textSecondary)
            }
            Box(Modifier.weight(1f))
            ZillitIconButton(
                icon = ZillitIcons.Info,
                contentDescription = "View details",
                onClick = { onEvent(PanelEvent.OpenHistoryDetail(entry.id)) },
            )
        }
        if (entry.targetTitle.isNotBlank()) {
            ZillitText("“${entry.targetTitle}”", style = serif(14.sp, FontWeight.Bold), color = colors.textPrimary)
        }
        if (entry.details.isNotBlank()) {
            ZillitText(entry.details, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.tint(DASH_ALPHA)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = buildString {
                    if (entry.createdAt > 0) append(DiaryFormat.stamp(entry.createdAt, state.zone)).append("  ·  ")
                    append("by ").append(name)
                    if (designation.isNotBlank()) append(" · ").append(designation)
                },
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            revision?.let { SmallBadge("REV ${it.number}") }
        }
    }
}

/** The person behind a row — the row's own name, else the crew list's — and their role. */
private fun performer(entry: HistoryEntry, state: BoxScheduleUiState): Pair<String, String> {
    val person = state.person(entry.performedById)
    val name = entry.performedByName.ifBlank { person?.fullName.orEmpty() }.ifBlank { "Someone" }
    return name to person?.designation?.localised().orEmpty()
}

/**
 * A history row in full: the revision's snapshot laid under the row, and
 * the record as it is now over both, when it still exists.
 */
@Composable
internal fun HistoryDetailDialog(
    state: BoxScheduleUiState,
    panel: HistoryPanel,
    entry: HistoryEntry,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val revision = DiaryHistory.revisionFor(entry, panel.revisions)
    val data = (revision?.snapshot ?: HistorySnapshot())
        .overlaidWith(DiaryHistory.live(entry, state.events, state.blocks, state.types))
    val tone = hexColor(entry.known?.color) ?: colors.textMuted
    val (name, designation) = performer(entry, state)
    ZillitDialogShell(
        title = "“${entry.targetTitle.ifBlank { data.title.ifBlank { "Untitled" } }}”",
        subtitle = listOfNotNull(
            entry.actionLabel,
            entry.targetLabel.takeIf { it.isNotBlank() },
            revision?.let { "REV ${it.number}" },
        ).joinToString(" · "),
        icon = ZillitIcons.Clock,
        onDismiss = { onEvent(PanelEvent.CloseHistoryDetail) },
        visible = true,
        width = 500.dp,
        actions = {
            Column(Modifier.weight(1f)) {
                ZillitText(name, style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold))
                ZillitText(
                    listOfNotNull(
                        designation.takeIf { it.isNotBlank() },
                        entry.createdAt.takeIf { it > 0 }?.let { DiaryFormat.fullStamp(it, state.zone) },
                    ).joinToString(" · "),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            ZillitButton(
                "Close",
                onClick = { onEvent(PanelEvent.CloseHistoryDetail) },
                variant = ButtonVariant.Secondary,
            )
        },
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(tone))
        if (data.color.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(swatchColor(data.color), size = 12.dp, square = true)
                ZillitText(data.color.uppercase(), style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
            }
        }
        SnapshotRows(state, panel, data)
    }
}

/** Every field the snapshot carries, in the web's order: what, when and where, the schedule, then who. */
@Composable
private fun SnapshotRows(state: BoxScheduleUiState, panel: HistoryPanel, data: HistorySnapshot) {
    SnapshotWhat(data, state.zone)
    SnapshotSchedule(state, data)
    SnapshotCall(data, state.zone)
    if (data.audience.isSet) AudienceRows(state, panel, data)
    if (data.organizerExcluded) {
        SnapshotRow(ZillitIcons.User, "Organizer") { Body("Excluded — organizer is not part of this event") }
    }
    if (data.externalEmails.isNotEmpty()) {
        SnapshotRow(ZillitIcons.Mail, "External Guests (${data.externalEmails.size})") {
            data.externalEmails.forEach { Body(it) }
        }
    }
}

@Composable
private fun SnapshotWhat(data: HistorySnapshot, zone: TimeZone) {
    if (data.eventType.isNotBlank()) SnapshotRow(ZillitIcons.Info, "Type") { SmallBadge(data.eventType.uppercase()) }
    if (data.description.isNotBlank()) {
        SnapshotRow(ZillitIcons.Edit, if (data.eventType == "note") "Note" else "Description") {
            Body(data.description)
        }
    }
    if (data.start > 0 || data.end > 0) {
        SnapshotRow(ZillitIcons.Clock, if (data.fullDay) "Date" else "When") {
            when {
                data.fullDay -> Body(DiaryFormat.shortWeekdayDate(data.start, zone))
                else -> {
                    if (data.start > 0) Body(DiaryFormat.dayStamp(data.start, zone))
                    if (data.end > 0 && data.end != data.start) Body(
                        "to ${DiaryFormat.dayStamp(data.end, zone)}",
                        muted = true,
                    )
                }
            }
            if (data.timezone.isNotBlank()) Body(data.timezone, muted = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnapshotSchedule(state: BoxScheduleUiState, data: HistorySnapshot) {
    state.blocks.firstOrNull { it.id == data.scheduleDayId }?.let { day ->
        SnapshotRow(ZillitIcons.Calendar, "Linked Schedule Day") {
            Swatched(day.color, day.title.ifBlank { day.typeName })
        }
    }
    state.types.firstOrNull { it.id == data.typeId }?.let { type ->
        SnapshotRow(ZillitIcons.Settings, "Schedule Type") { Swatched(type.color, type.title) }
    }
    if (data.calendarDays.isNotEmpty()) {
        SnapshotRow(ZillitIcons.Calendar, "Calendar Days (${data.calendarDays.size})") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                data.calendarDays.sorted().take(MAX_DAYS).forEach { SmallBadge(DiaryFormat.monthDay(it, state.zone)) }
                if (data.calendarDays.size > MAX_DAYS) Body("+${data.calendarDays.size - MAX_DAYS} more", muted = true)
            }
        }
    }
}

@Composable
private fun SnapshotCall(data: HistorySnapshot, zone: TimeZone) {
    if (data.location.isNotBlank()) SnapshotRow(ZillitIcons.Pin, "Location") { Body(data.location) }
    if (data.callType.isNotBlank()) {
        SnapshotRow(ZillitIcons.Phone, "Call Type") { Body(DiaryFormat.callTypeLabel(data.callType)) }
    }
    if (data.reminder.isNotBlank() && data.reminder != "none") {
        SnapshotRow(ZillitIcons.Bell, "Reminder") { Body(DiaryFormat.reminderLabel(data.reminder)) }
    }
    if (data.repeatStatus.isNotBlank() && data.repeatStatus != "none") {
        SnapshotRow(ZillitIcons.Reload, "Repeat") {
            Body(data.repeatStatus.replaceFirstChar { it.uppercase() })
            if (data.repeatEndDate > 0) Body(
                "Until ${DiaryFormat.shortWeekdayDate(data.repeatEndDate, zone)}",
                muted = true,
            )
        }
    }
}

@Composable
private fun Swatched(hex: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(swatchColor(hex), size = 10.dp, square = true)
        Body(text)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudienceRows(state: BoxScheduleUiState, panel: HistoryPanel, data: HistorySnapshot) {
    val audience = data.audience
    SnapshotRow(ZillitIcons.Users, "Distribute To") {
        Body(if (audience.mode == AudienceMode.Presets) "Saved Preset" else audience.mode.label)
        val names = audience.userIds.mapNotNull { state.person(it)?.fullName?.takeIf { name -> name.isNotBlank() } }
        if (names.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                names.take(MAX_NAMES).forEach { SmallBadge(it) }
                if (names.size > MAX_NAMES) Body("+${names.size - MAX_NAMES} more", muted = true)
            }
        }
        val counts = buildList {
            if (audience.userIds.isNotEmpty() && names.isEmpty()) add(plural(audience.userIds.size, "user"))
            if (audience.departmentIds.isNotEmpty()) add(plural(audience.departmentIds.size, "department"))
        }
        if (counts.isNotEmpty()) Body(counts.joinToString(" · "), muted = true)
        audience.presetId?.let { id -> panel.presets.firstOrNull { it.id == id }?.name }?.let { Body("Preset: $it") }
    }
}

@Composable
private fun SnapshotRow(icon: ImageVector, label: String, content: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.width(28.dp).height(28.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceSunken),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = icon, tint = colors.textMuted, size = 13.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Caption(label)
            content()
        }
    }
}

@Composable
private fun Body(text: String, muted: Boolean = false) {
    ZillitText(
        text,
        style = ZillitTheme.typography.bodyMedium,
        color = if (muted) ZillitTheme.colors.textMuted else ZillitTheme.colors.textPrimary,
    )
}

private fun plural(count: Int, noun: String): String = "$count $noun${if (count == 1) "" else "s"}"

private const val MAX_DAYS = 12
private const val MAX_NAMES = 8
private const val DASH_ALPHA = 0.6f

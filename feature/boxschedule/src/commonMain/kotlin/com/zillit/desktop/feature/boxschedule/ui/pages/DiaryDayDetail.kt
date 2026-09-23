package com.zillit.desktop.feature.boxschedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.AudienceMode
import com.zillit.desktop.feature.boxschedule.domain.DiaryAudience
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryFormat
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.domain.filedOn
import com.zillit.desktop.feature.boxschedule.domain.isPersonalNote
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.DayEvent
import com.zillit.desktop.feature.boxschedule.ui.EntryEvent
import com.zillit.desktop.feature.boxschedule.ui.PageEvent
import com.zillit.desktop.feature.boxschedule.ui.ScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.rememberDiaryFace

/**
 * One schedule on one date — `ScheduleDayDetail`: its heading and edit
 * controls, then the events and notes filed on it for that date.
 */
@Composable
internal fun ScheduleDayDetail(
    block: ScheduleBlock,
    dayKey: Long,
    state: BoxScheduleUiState,
    mode: DetailMode,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val onDay = remember(state.events, block.id, dayKey, state.zone) {
        state.events.filter { it.scheduleDayId == block.id && it.filedOn(state.zone) == dayKey }
    }
    val events = onDay.filter { it.kind == DiaryKind.Event }
    val notes = onDay.filter { it.kind == DiaryKind.Note }
    val dayNumber = state.rows.firstOrNull { it.block.id == block.id && it.date == dayKey }?.dayNumber
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(swatchColor(block.color)))
        Column(
            Modifier.weight(1f).padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            DetailHeading(block, dayKey, dayNumber, state, mode, onEvent)
            if (!mode.hideEvents) {
                DetailSection(
                    title = str(S.dd_events),
                    icon = ZillitIcons.Clock,
                    count = events.size,
                    createLabel = str(S.create_event).takeIf { !mode.readOnly && !mode.hideInlineCreate },
                    onCreate = { onEvent(EntryEvent.NewEntry(DiaryKind.Event, dayKey, block.id)) },
                    empty = str(S.desktop_bs_no_events_yet),
                ) {
                    events.forEach { event -> EntryRow(event, state, mode.readOnly, onEvent, mayCall = false) }
                }
            }
            DetailSection(
                title = str(S.notes),
                icon = ZillitIcons.Edit,
                count = notes.size,
                createLabel = str(S.bs_create_note).takeIf { !mode.readOnly && !mode.hideInlineCreate },
                onCreate = { onEvent(EntryEvent.NewEntry(DiaryKind.Note, dayKey, block.id)) },
                empty = str(S.desktop_no_notes_yet),
            ) {
                NoteGroups(notes) { note -> EntryRow(note, state, mode.readOnly, onEvent, mayCall = false) }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(2.dp).background(colors.border))
}

@Composable
private fun DetailHeading(
    block: ScheduleBlock,
    dayKey: Long,
    dayNumber: Int?,
    state: BoxScheduleUiState,
    mode: DetailMode,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Dot(swatchColor(block.color), size = 12.dp, square = true)
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = when {
                    block.isDayOff -> str(S.desktop_day_type_day_off)
                    dayNumber != null -> str(S.desktop_bs_type_day_number, block.typeName, dayNumber)
                    else -> block.typeName
                },
                style = serif(18.sp, FontWeight.Bold, spacing = 0.3.sp),
                color = colors.textPrimary,
            )
            ZillitText(
                text = DiaryFormat.longDate(
                    dayKey,
                    state.zone,
                ) + if (block.title.isNotBlank()) " — ${block.title}" else "",
                style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = colors.textMuted,
            )
        }
        if (!mode.readOnly) {
            ZillitButton(
                text = str(S.edit),
                onClick = { onEvent(ScheduleEvent.EditSchedule(block.id, dayKey)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
            )
            ZillitButton(
                text = str(S.delete),
                onClick = { onEvent(ScheduleEvent.AskDeleteDay(block.id, dayKey)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Trash,
            )
        }
    }
}

/** A titled list — EVENTS or NOTES — with its count, a create button, and an empty line. */
@Composable
internal fun DetailSection(
    title: String,
    icon: ImageVector,
    count: Int,
    createLabel: String?,
    onCreate: () -> Unit,
    empty: String,
    rows: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitIcon(icon = icon, tint = colors.warning, size = 14.dp)
            Caption(title, color = colors.textSecondary)
            if (count > 0) CountPill(count)
            Box(Modifier.weight(1f))
            createLabel?.let {
                ZillitButton(
                    it,
                    onClick = onCreate,
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        }
        if (count == 0) {
            ZillitText(
                empty,
                style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = colors.textMuted,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { rows() }
        }
    }
}

/** Notes in two groups, Personal Notes first — ZL-21253. */
@Composable
internal fun NoteGroups(notes: List<DiaryEvent>, row: @Composable (DiaryEvent) -> Unit) {
    val (personal, general) = notes.partition { it.isPersonalNote }
    if (personal.isNotEmpty()) Caption(str(S.bs_pdf_notes_label))
    personal.forEach { row(it) }
    if (general.isNotEmpty()) Caption(str(S.ce_note_type_general))
    general.forEach { row(it) }
}

/**
 * An event or note as the diary's detail lists show it: the details, the
 * audience, and — unless read-only — Join, Edit and Remove. The row opens
 * the entry's complete details.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EntryRow(
    entry: DiaryEvent,
    state: BoxScheduleUiState,
    readOnly: Boolean,
    onEvent: (BoxScheduleEvent) -> Unit,
    mayCall: Boolean,
    showDate: Boolean = false,
) {
    val colors = ZillitTheme.colors
    val isEvent = entry.kind == DiaryKind.Event
    val accent = hexColor(entry.color) ?: if (isEvent) EVENT_BLUE else colors.textMuted
    val joinable = mayCall && entry.canJoin(state)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .clickable { onEvent(DayEvent.ViewEntry(entry.listKey)) }
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(
            Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            EntryRowText(entry, state, showDate)
            EntryBadges(entry, state)
        }
        if (!readOnly || joinable) {
            Row(Modifier.padding(top = 10.dp, end = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (joinable) ZillitButton(
                    str(S.txt_join),
                    onClick = { onEvent(PageEvent.JoinCall(entry.listKey)) },
                    size = ButtonSize.Small,
                )
                if (!readOnly) {
                    RowAction(str(S.edit), ZillitIcons.Edit, onClick = { onEvent(EntryEvent.EditEntry(entry.listKey)) })
                    RowAction(
                        str(S.remove),
                        ZillitIcons.Trash,
                        onClick = { onEvent(EntryEvent.AskDelete(entry.listKey)) },
                        danger = true,
                    )
                }
            }
        }
    }
}

/** The time, title, date, place and text of an entry row. */
@Composable
private fun EntryRowText(entry: DiaryEvent, state: BoxScheduleUiState, showDate: Boolean) {
    val colors = ZillitTheme.colors
    val isEvent = entry.kind == DiaryKind.Event
    val time = if (isEvent) DiaryFormat.timeRange(entry, state.zone) else ""
    if (time.isNotBlank()) ZillitText(time, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
    ZillitText(
        text = entry.title.ifBlank { if (isEvent) str(S.new_box_untitled) else str(S.desktop_bs_untitled_note) },
        style = ZillitTheme.typography.titleSmall,
        color = hexColor(entry.textColor) ?: colors.textPrimary,
    )
    if (showDate) {
        ZillitText(
            DiaryFormat.shortDay(entry.anchor, state.zone),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
    }
    if (entry.location.isNotBlank()) LocationLink(entry)
    if (entry.body.isNotBlank()) {
        ZillitText(entry.body, style = ZillitTheme.typography.bodySmall, color = colors.textMuted, maxLines = 4)
    }
}

/** The call is joinable, has a room, and has not long ended. */
internal fun DiaryEvent.canJoin(state: BoxScheduleUiState): Boolean =
    isCallJoinable && hasCallRoom && isCallOpen(state.nowMillis)

/** Call type, reminder, zone, repeat and audience — the small facts under an event. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryBadges(entry: DiaryEvent, state: BoxScheduleUiState) {
    val badges = buildList {
        if (entry.kind == DiaryKind.Event) {
            if (entry.callType.isNotBlank()) add(DiaryFormat.callBadge(entry.callType))
            if (entry.reminder.isNotBlank() && entry.reminder != "none") {
                add(str(S.desktop_bs_reminder_value, DiaryFormat.reminderLabel(entry.reminder)))
            }
            if (entry.timezone.isNotBlank()) add(DiaryFormat.timezoneLabel(entry.timezone))
            if (entry.isRecurring && entry.repeatStatus.isNotBlank() && entry.repeatStatus != "none") {
                add(str(S.desktop_bs_repeats_value, entry.repeatStatus))
            }
        }
        if (entry.calendarSourced) add(str(S.desktop_bs_from_calendar))
    }
    if (badges.isEmpty() && !entry.audience.isSet) return
    FlowRow(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        badges.forEach { SmallBadge(it) }
        AudienceChip(entry.audience, state)
    }
}

@Composable
internal fun SmallBadge(text: String) {
    val colors = ZillitTheme.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        ZillitText(text, style = ZillitTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1)
    }
}

/**
 * Who an entry is distributed to — `DistributeAudienceChip`. Users show as
 * faces and names, five inline and the rest behind "+N more"; every other
 * mode is one compact chip. Nothing is drawn when no audience was set.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AudienceChip(audience: DiaryAudience, state: BoxScheduleUiState) {
    val label = audience.chipLabel ?: return
    val colors = ZillitTheme.colors
    if (audience.mode != AudienceMode.Users) {
        Row(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(colors.accentSoft)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZillitIcon(icon = ZillitIcons.Users, tint = colors.accentText, size = 10.dp)
            ZillitText(label, style = ZillitTheme.typography.labelSmall, color = colors.accentText)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ZillitIcon(icon = ZillitIcons.Users, tint = colors.textMuted, size = 11.dp)
            ZillitText(
                str(S.desktop_bs_distributed_to_n, audience.userIds.size),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            audience.userIds.take(VISIBLE_PEOPLE).forEach { id -> PersonPill(id, state) }
            val rest = audience.userIds.drop(VISIBLE_PEOPLE)
            if (rest.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    MoreChip(str(S.desktop_n_more, rest.size), onClick = { open = true })
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        Column(Modifier.width(240.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ZillitText(
                                str(S.desktop_bs_n_more_users, rest.size),
                                style = ZillitTheme.typography.titleSmall,
                            )
                            rest.forEach { id -> PersonLine(id, state) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonPill(userId: String, state: BoxScheduleUiState) {
    val colors = ZillitTheme.colors
    val name = state.person(userId)?.fullName?.ifBlank { null } ?: str(S.user_label)
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(999.dp))
            .padding(start = 2.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ZillitAvatar(name = name, image = rememberDiaryFace(userId), userId = userId, size = PILL_FACE)
        ZillitText(name, style = ZillitTheme.typography.labelSmall, color = colors.textPrimary, maxLines = 1)
    }
}

@Composable
internal fun PersonLine(userId: String, state: BoxScheduleUiState) {
    val person = state.person(userId)
    val name = person?.fullName?.ifBlank { null } ?: str(S.user_label)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZillitAvatar(name = name, image = rememberDiaryFace(userId), userId = userId, size = LINE_FACE)
        Column {
            ZillitText(name, style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            person?.subtitle?.takeIf { it.isNotBlank() }?.let {
                ZillitText(
                    it,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A place, underlined, that opens in Google Maps — at its point when it was picked on the map. */
@Composable
internal fun LocationLink(entry: DiaryEvent) {
    val uri = LocalUriHandler.current
    val colors = ZillitTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { runCatching { uri.openUri(mapUrl(entry)) } },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitIcon(icon = ZillitIcons.Pin, tint = colors.accentText, size = 11.dp)
        ZillitText(
            text = entry.location,
            style = ZillitTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline),
            color = colors.accentText,
            maxLines = 2,
        )
    }
}

/** `google.com/maps?q=lat,lng` for a picked place, a search for a typed one. */
internal fun mapUrl(entry: DiaryEvent): String {
    val lat = entry.locationLat
    val lng = entry.locationLng
    return if (lat != null && lng != null) {
        "https://www.google.com/maps?q=$lat,$lng"
    } else {
        "https://www.google.com/maps/search/${percentEncode(entry.location)}"
    }
}

private fun percentEncode(text: String): String = buildString {
    text.encodeToByteArray().forEach { byte ->
        val c = byte.toInt().toChar()
        if (c.isLetterOrDigit() && byte >= 0 || c in "-_.~") {
            append(c)
        } else {
            append('%').append((byte.toInt() and BYTE_MASK).toString(HEX).uppercase().padStart(2, '0'))
        }
    }
}

private const val VISIBLE_PEOPLE = 5
private const val BYTE_MASK = 0xFF
private const val HEX = 16
private val PILL_FACE = 22.dp
private val LINE_FACE = 28.dp

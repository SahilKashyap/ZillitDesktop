package com.zillit.desktop.feature.settings.preferences

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.ui.SettingRow

/**
 * The web's App Preferences, in its order: Default Home Unit, Notifications,
 * Location On/Off — rows of the one Settings card that holds them. The first
 * two are a team production's only, as the web hides them on a personal one.
 *
 * Re-reads the profile whenever it comes into view, so a choice made on a
 * phone since is what the page shows.
 */
@Composable
fun AppPreferencesCards(state: AppPreferencesUiState, onEvent: (AppPreferencesEvent) -> Unit) {
    LaunchedEffect(Unit) { onEvent(AppPreferencesEvent.Refresh) }
    if (state.isTeamProject) {
        DefaultUnitRow(state, onEvent)
        SettingRow(
            title = str(S.notifications),
            detail = str(S.desktop_manage_notifications_detail),
            icon = ZillitIcons.Bell,
        ) {
            ZillitButton(
                text = str(S.desktop_manage_notifications),
                onClick = { onEvent(AppPreferencesEvent.OpenNotifications) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
    }
    LocationRow(state, onEvent)
}

/**
 * What draws over the whole page: the notifications drawer and the toast.
 * Composed at the page root, since neither is a popup.
 */
@Composable
fun AppPreferencesOverlays(state: AppPreferencesUiState, onEvent: (AppPreferencesEvent) -> Unit) {
    NotificationsDrawerPanel(state.notifications, onEvent)
    ZillitToast(
        message = state.toast?.text,
        onDismiss = { onEvent(AppPreferencesEvent.ToastDismissed) },
        tone = if (state.toast?.isError == true) ZillitToastTone.Danger else ZillitToastTone.Success,
    )
}

@Composable
private fun DefaultUnitRow(state: AppPreferencesUiState, onEvent: (AppPreferencesEvent) -> Unit) {
    SettingRow(
        title = str(S.desktop_default_home_unit),
        detail = str(S.desktop_default_home_unit_detail),
        icon = ZillitIcons.Grid,
    ) {
        ZillitSelect(
            value = state.defaultUnit,
            options = state.units,
            onSelect = { unit -> unit?.let { onEvent(AppPreferencesEvent.DefaultUnitPicked(it.id)) } },
            // `unit_name` is a label key — `call_sheet`, `calendar_label`.
            label = { it?.name?.localised() ?: str(S.select_default_home_unit) },
            enabled = !state.savingUnit && state.units.isNotEmpty(),
            showInitials = false,
            // Fixed, like the language select: a select sized by its
            // content fills the row and crushes the title column.
            modifier = Modifier.width(UNIT_SELECT_WIDTH).testTag(DEFAULT_UNIT_TAG),
        )
    }
}

@Composable
private fun LocationRow(state: AppPreferencesUiState, onEvent: (AppPreferencesEvent) -> Unit) {
    val location = state.location
    SettingRow(
        title = str(S.location_on_off),
        detail = str(S.desktop_location_on_off_detail),
        icon = ZillitIcons.Pin,
        body = if (state.showsSchedule) {
            { LocationSchedule(state, onEvent) }
        } else {
            null
        },
    ) {
        ZillitSwitch(
            checked = location.enabled,
            onCheckedChange = { onEvent(AppPreferencesEvent.LocationToggled(it)) },
            enabled = !state.togglingLocation,
            modifier = Modifier.testTag(LOCATION_SWITCH_TAG),
        )
    }
}

@Composable
private fun LocationSchedule(state: AppPreferencesUiState, onEvent: (AppPreferencesEvent) -> Unit) {
    val location = state.location
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        ZillitCheckbox(
            checked = location.mode == LocationMode.Everyday,
            onCheckedChange = { onEvent(AppPreferencesEvent.ModeChanged(LocationMode.Everyday)) },
            label = str(S.every_day),
        )
        ZillitCheckbox(
            checked = location.mode == LocationMode.SelectDays,
            onCheckedChange = { onEvent(AppPreferencesEvent.ModeChanged(LocationMode.SelectDays)) },
            label = str(S.txt_select_days),
        )
    }

    when (location.mode) {
        LocationMode.Everyday -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            LabelledTime(str(S.start_time), location.everyday.startMinute, Modifier.weight(1f)) {
                onEvent(AppPreferencesEvent.EverydayStartChanged(it))
            }
            LabelledTime(str(S.end_time), location.everyday.endMinute, Modifier.weight(1f)) {
                onEvent(AppPreferencesEvent.EverydayEndChanged(it))
            }
        }
        LocationMode.SelectDays -> DaySchedule(location, onEvent)
    }

    ZillitButton(
        text = str(S.txt_update_location),
        onClick = { onEvent(AppPreferencesEvent.UpdateLocation) },
        loading = state.savingSchedule,
        enabled = !state.togglingLocation,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The web's `DaySelector` and a row of two times per ticked day. */
@Composable
private fun DaySchedule(location: LocationSharing, onEvent: (AppPreferencesEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        WEEK_DAYS.forEach { day ->
            ZillitButton(
                text = dayLabel(day),
                onClick = { onEvent(AppPreferencesEvent.DayToggled(day)) },
                variant = if (day in location.days) ButtonVariant.Primary else ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
    }
    location.days.toSortedMap().forEach { (day, window) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.small)
                .padding(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = dayLabel(day),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.width(DAY_LABEL_WIDTH),
            )
            TimeSelect(window.startMinute, Modifier.weight(1f)) {
                onEvent(AppPreferencesEvent.DayStartChanged(day, it))
            }
            ZillitText(text = "-", style = ZillitTheme.typography.bodyMedium)
            TimeSelect(window.endMinute, Modifier.weight(1f)) {
                onEvent(AppPreferencesEvent.DayEndChanged(day, it))
            }
        }
    }
}

@Composable
private fun LabelledTime(label: String, minute: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row {
            ZillitText(
                text = label,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
            ZillitText(text = " *", style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.danger)
        }
        TimeSelect(minute, Modifier.fillMaxWidth(), onPick)
    }
}

/**
 * A time of day, as the web's 12-hour `TimePicker` shows it. Quarter hours
 * plus 11:59 PM — the web's end of day — and whatever is stored, so a time
 * set on a phone to the minute is still the one shown. Typing filters the
 * list, so "9:3" finds 9:30 AM.
 */
@Composable
private fun TimeSelect(minute: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    val options = remember(minute) { (timeSlots() + minute).distinct().sorted() }
    ZillitSelect(
        value = minute,
        options = options,
        onSelect = onPick,
        label = ::clockLabel,
        searchable = true,
        showInitials = false,
        fieldLeading = {
            ZillitIcon(ZillitIcons.Clock, tint = ZillitTheme.colors.textMuted, size = CLOCK_ICON)
        },
        modifier = modifier,
    )
}

private fun timeSlots(): List<Int> = (0 until MINUTES_PER_DAY step SLOT_MINUTES).toList() + TimeWindow.LAST_MINUTE

/** `12:00 AM`, `11:59 PM` — the web's `hh:mm A`. */
internal fun clockLabel(minute: Int): String {
    val hour = minute / MINUTES_PER_HOUR
    val hour12 = (hour % HOURS_PER_HALF_DAY).let { if (it == 0) HOURS_PER_HALF_DAY else it }
    val suffix = if (hour < HOURS_PER_HALF_DAY) "AM" else "PM"
    return "${hour12.toString().padStart(2, '0')}:${(minute % MINUTES_PER_HOUR).toString().padStart(2, '0')} $suffix"
}

// -- Manage Notifications ------------------------------------------------------

/**
 * The web's right-hand drawer: a search, then each section as a card — a
 * single switch when it has no tools, otherwise a foldable heading over its
 * tools, and under a tool its activities. A section switched off greys its
 * tools; a tool switched off greys its activities.
 *
 * Only the close button closes it — the web's mask does not.
 */
@Composable
private fun NotificationsDrawerPanel(drawer: NotificationsDrawer, onEvent: (AppPreferencesEvent) -> Unit) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = drawer.open
    AnimatedVisibility(visibleState = transition, enter = fadeIn(tween(ANIM_MS)), exit = fadeOut(tween(ANIM_MS))) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ZillitTheme.colors.scrim.copy(alpha = SCRIM_ALPHA))
                // Swallows clicks so the page beneath cannot be used through it.
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.CenterEnd,
        ) {
            AnimatedVisibility(
                visibleState = transition,
                enter = slideInHorizontally(tween(ANIM_MS)) { it },
                exit = slideOutHorizontally(tween(ANIM_MS)) { it },
            ) {
                DrawerBody(drawer, onEvent)
            }
        }
    }
}

@Composable
private fun DrawerBody(drawer: NotificationsDrawer, onEvent: (AppPreferencesEvent) -> Unit) {
    Column(
        modifier = Modifier
            .width(DRAWER_WIDTH)
            .fillMaxHeight()
            .background(ZillitTheme.colors.surface)
            .testTag(NOTIFICATIONS_DRAWER_TAG),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.close),
                onClick = { onEvent(AppPreferencesEvent.CloseNotifications) },
            )
            Column {
                ZillitText(text = str(S.desktop_manage_notifications), style = ZillitTheme.typography.titleMedium)
                ZillitText(
                    text = str(S.desktop_manage_notifications_subtitle),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
        Box(Modifier.fillMaxWidth().background(ZillitTheme.colors.divider).padding(top = HAIRLINE))
        ZillitSearchField(
            value = drawer.query,
            onValueChange = { onEvent(AppPreferencesEvent.NotificationQuery(it)) },
            placeholder = str(S.desktop_search_module),
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
        )
        val sections = visibleSections(drawer.sections, drawer.query)
        when {
            drawer.loading -> Column(
                Modifier.padding(ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                repeat(SKELETON_ROWS) { ZillitSkeletonBar(Modifier.fillMaxWidth()) }
            }
            sections.isEmpty() -> ZillitEmptyState(title = str(S.desktop_no_matching_modules))
            else -> ZillitScrollColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ZillitTheme.spacing.lg,
                    end = ZillitTheme.spacing.lg,
                    bottom = ZillitTheme.spacing.lg,
                ),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                sections.forEach { section ->
                    SectionCard(section, open = section.name !in drawer.collapsed, onEvent = onEvent)
                }
            }
        }
    }
}

/**
 * Sorted by what they read as, sections and tools alike, and narrowed by the
 * search: a section stays when its own name matches (with only the tools that
 * match), or when any tool does.
 */
private fun visibleSections(sections: List<NotificationSection>, query: String): List<NotificationSection> {
    val needle = query.trim()
    return sections
        .map { section -> section.copy(tools = section.tools.sortedBy { it.name.label().lowercase() }) }
        .sortedBy { it.name.label().lowercase() }
        .mapNotNull { section ->
            val tools = section.tools.filter { it.name.label().contains(needle, ignoreCase = true) }
            val sectionMatches = section.name.label().contains(needle, ignoreCase = true)
            if (sectionMatches || tools.isNotEmpty()) section.copy(tools = tools) else null
        }
}

@Composable
private fun SectionCard(section: NotificationSection, open: Boolean, onEvent: (AppPreferencesEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.large),
    ) {
        val sectionSwitch = @Composable {
            ZillitSwitch(
                checked = section.enabled,
                onCheckedChange = { onEvent(AppPreferencesEvent.SectionSwitched(section.name, it)) },
            )
        }
        if (section.tools.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = section.name.label(),
                    style = ZillitTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                sectionSwitch()
            }
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onEvent(AppPreferencesEvent.SectionFolded(section.name)) }
                .padding(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitIcon(
                icon = ZillitIcons.ChevronRight,
                tint = ZillitTheme.colors.textMuted,
                size = CHEVRON,
                modifier = Modifier.rotate(if (open) OPEN_ROTATION else 0f),
            )
            ZillitText(
                text = section.name.label(),
                style = ZillitTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            sectionSwitch()
        }
        if (open) {
            section.tools.forEach { tool ->
                Box(Modifier.fillMaxWidth().background(ZillitTheme.colors.divider).padding(top = HAIRLINE))
                ToolRow(tool, sectionOn = section.enabled, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun ToolRow(tool: NotificationTool, sectionOn: Boolean, onEvent: (AppPreferencesEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            start = TOOL_INDENT,
            end = ZillitTheme.spacing.lg,
            top = ZillitTheme.spacing.md,
            bottom = ZillitTheme.spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = tool.name.label(),
                style = ZillitTheme.typography.bodyLarge,
                color = if (sectionOn) ZillitTheme.colors.textPrimary else ZillitTheme.colors.textDisabled,
                modifier = Modifier.weight(1f),
            )
            ZillitSwitch(
                checked = tool.enabled,
                onCheckedChange = { onEvent(AppPreferencesEvent.ToolSwitched(tool.name, it)) },
                enabled = sectionOn,
            )
        }
        val activitiesOn = sectionOn && tool.enabled
        tool.activities.forEach { activity ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = ZillitTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = activity.name.label(),
                    style = ZillitTheme.typography.bodyMedium,
                    color = if (activitiesOn) ZillitTheme.colors.textSecondary else ZillitTheme.colors.textDisabled,
                    modifier = Modifier.weight(1f),
                )
                ZillitSwitch(
                    checked = activity.enabled,
                    onCheckedChange = { onEvent(AppPreferencesEvent.ActivitySwitched(tool.name, activity.name, it)) },
                    enabled = activitiesOn,
                )
            }
        }
    }
}

/**
 * A label key in the reader's language — never blank: a key with no
 * translation reads humanised (`chat_label` → `Chat`), as the web falls back.
 */
private fun String.label(): String = localised().trim().ifBlank {
    removeSuffix("_label").split('_', '-').filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}

internal const val DEFAULT_UNIT_TAG = "prefs-default-unit"
internal const val LOCATION_SWITCH_TAG = "prefs-location-switch"
internal const val NOTIFICATIONS_DRAWER_TAG = "prefs-notifications-drawer"

private val HAIRLINE = 1.dp
private val UNIT_SELECT_WIDTH = 240.dp
private val CLOCK_ICON = 16.dp
private val CHEVRON = 14.dp
private val DAY_LABEL_WIDTH = 40.dp
private val DRAWER_WIDTH = 440.dp
private val TOOL_INDENT = 48.dp
private const val OPEN_ROTATION = 90f
private const val SCRIM_ALPHA = 0.45f
private const val ANIM_MS = 200
private const val SKELETON_ROWS = 6
private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * 60
private const val SLOT_MINUTES = 15
private const val HOURS_PER_HALF_DAY = 12

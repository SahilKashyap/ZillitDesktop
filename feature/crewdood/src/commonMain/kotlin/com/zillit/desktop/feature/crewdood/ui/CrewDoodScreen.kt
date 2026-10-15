package com.zillit.desktop.feature.crewdood.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.crewdood.domain.DoodBand

/**
 * Crew DOOD — Day Out Of Days for the crew.
 *
 * Every project member, in the crew list's order, against the production's
 * calendar: SW on the day an active or deactivated deal starts, W through it,
 * WF on its last working day. Prep / Shoot / Post come from the Production
 * Calendar; days off and holidays come from the project calendar the toolbar
 * edits.
 *
 * The top bar is the Time Card surface's, value for value — a back chip and
 * the tool's name in its amber crumb — with the design's legend on the right.
 */
@Composable
fun CrewDoodScreen(
    state: CrewDoodUiState,
    onEvent: (CrewDoodEvent) -> Unit,
    controller: DoodGridController,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    Box(modifier.fillMaxSize().background(colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(onEvent)
            Toolbar(state, onEvent)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> LoadingRows()

                    state.error != null -> ZillitErrorState(
                        message = state.error,
                        onRetry = { onEvent(CrewDoodEvent.Reload) },
                    )

                    !state.hasCrew -> ZillitEmptyState(
                        title = str(S.dm_nda_no_crew),
                        icon = ZillitIcons.Users,
                    )

                    state.groups.isEmpty() -> ZillitEmptyState(
                        title = str(S.desktop_cdood_no_match),
                        icon = ZillitIcons.Search,
                        action = {
                            ZillitButton(
                                text = str(S.ah_clear),
                                onClick = { onEvent(CrewDoodEvent.Search("")) },
                                variant = ButtonVariant.Tertiary,
                                size = ButtonSize.Small,
                            )
                        },
                    )

                    else -> CrewDoodGrid(state, onEvent, controller)
                }
            }
        }
        if (state.calendarOpen) {
            CrewDoodCalendarDialog(
                calendar = state.calendar,
                saving = state.saving,
                onSave = { onEvent(CrewDoodEvent.SaveCalendar(it)) },
                onDismiss = { onEvent(CrewDoodEvent.CloseCalendar) },
            )
        }
        ZillitToast(
            message = state.toast?.text,
            tone = if (state.toast?.success == true) ZillitToastTone.Success else ZillitToastTone.Danger,
            onDismiss = { onEvent(CrewDoodEvent.DismissToast) },
        )
    }
}

@Composable
private fun TopBar(onEvent: (CrewDoodEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val palette = doodPalette()
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIconButton(
                icon = ZillitIcons.ChevronLeft,
                contentDescription = str(S.desktop_csync_back_to_tools),
                onClick = { onEvent(CrewDoodEvent.Back) },
            )
            ZillitText(
                text = str(S.desktop_cdood_label).uppercase(),
                style = ZillitTheme.typography.titleMedium,
                color = palette.crumb,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Legend()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

/** SW, W, WF, SWF, OFF · Day off, HOL · Holiday — the design's key. */
@Composable
private fun Legend() {
    val palette = doodPalette()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        LegendItem(palette.accent, str(S.desktop_cdood_legend_start))
        LegendItem(palette.work, str(S.desktop_cdood_legend_work))
        LegendItem(palette.finish, str(S.desktop_cdood_legend_finish))
        LegendItem(palette.accent, str(S.desktop_cdood_legend_single_day), half = palette.finish)
        LegendItem(palette.weekend, str(S.desktop_cdood_legend_off))
        LegendItem(palette.holiday, str(S.desktop_cdood_legend_hol))
    }
}

@Composable
private fun LegendItem(color: Color, text: String, half: Color? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Row(Modifier.size(SWATCH).clip(ZillitTheme.shapes.small)) {
            Box(Modifier.weight(1f).fillMaxSize().background(color))
            half?.let { Box(Modifier.weight(1f).fillMaxSize().background(it)) }
        }
        ZillitText(
            text = text,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textSecondary,
            maxLines = 1,
        )
    }
}

/**
 * Find, the department picker, Days / Weeks, Jump to, All / Booked, the
 * calendar editor and Export.
 *
 * The toolbar never shrinks a control, which is the web's rule too
 * (`.cd-bar > * { flex: none }`): under 1280dp it drops the group labels
 * (`@media (max-width: 1280px) { .cd-gl { display: none } }`) and under that it
 * scrolls sideways (`overflow-x: auto`). As one plain Row it did neither — at
 * the workspace window's own 1024dp the trailing buttons were squeezed until
 * "Days off & holidays" wrapped to a letter a line, three hundred dp tall, and
 * Export was pushed off the edge altogether.
 */
@Composable
private fun Toolbar(state: CrewDoodUiState, onEvent: (CrewDoodEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().background(colors.surface)) {
            val labels = maxWidth >= LABELS_FROM
            val fits = maxWidth >= if (labels) FITS_WITH_LABELS else FITS_WITHOUT_LABELS
            val bar = Modifier
                .then(if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()))
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm)
            Row(
                bar,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                if (labels) GroupLabel(str(S.desktop_cr_find))
                ZillitSearchField(
                    value = state.query,
                    onValueChange = { onEvent(CrewDoodEvent.Search(it)) },
                    placeholder = str(S.desktop_cdood_search_placeholder),
                    modifier = Modifier.width(SEARCH_WIDTH),
                )
                DepartmentPicker(state, onEvent)
                // A weight has no meaning inside a horizontal scroll, where the
                // row is measured against an unbounded width.
                if (fits) Spacer(Modifier.weight(1f)) else Spacer(Modifier.width(ZillitTheme.spacing.md))
                ViewSwitch(state, onEvent, labels)
                if (state.model.hasPhases) JumpTo(state, onEvent, labels)
                FilterSwitch(state, onEvent, labels)
                ToolbarActions(state, onEvent)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

@Composable
private fun ViewSwitch(state: CrewDoodUiState, onEvent: (CrewDoodEvent) -> Unit, labels: Boolean) {
    if (labels) GroupLabel(str(S.view))
    DoodSegmented(
        options = listOf(false to str(S.dm_ds_unit_days), true to str(S.dm_ds_unit_weeks)),
        selected = state.weeks,
        onSelect = { onEvent(CrewDoodEvent.SetWeeks(it)) },
    )
}

/** Prep / Shoot / Post. A band the chart has none of is dead, so it is disabled. */
@Composable
private fun JumpTo(state: CrewDoodUiState, onEvent: (CrewDoodEvent) -> Unit, labels: Boolean) {
    if (labels) GroupLabel(str(S.desktop_cdood_jump_to))
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        DoodBand.entries.forEach { band ->
            ZillitButton(
                text = DoodWords.band(band),
                onClick = { onEvent(CrewDoodEvent.JumpTo(band)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = band in state.bands && !state.busy,
            )
        }
    }
}

@Composable
private fun FilterSwitch(state: CrewDoodUiState, onEvent: (CrewDoodEvent) -> Unit, labels: Boolean) {
    if (labels) GroupLabel(str(S.filter))
    DoodSegmented(
        options = listOf(false to str(S.all), true to str(S.desktop_ad_attendance_booked)),
        selected = state.bookedOnly,
        onSelect = { onEvent(CrewDoodEvent.SetBookedOnly(it)) },
    )
}

@Composable
private fun ToolbarActions(state: CrewDoodUiState, onEvent: (CrewDoodEvent) -> Unit) {
    ZillitButton(
        text = str(S.desktop_cdood_calendar_button),
        onClick = { onEvent(CrewDoodEvent.OpenCalendar) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Calendar,
        enabled = !state.busy,
    )
    ZillitButton(
        text = str(S.asset_export),
        onClick = { onEvent(CrewDoodEvent.Export) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Download,
        enabled = !state.busy && !state.exporting,
        loading = state.exporting,
    )
}

@Composable
private fun DepartmentPicker(state: CrewDoodUiState, onEvent: (CrewDoodEvent) -> Unit) {
    val all = DoodDeptOption(key = "", name = str(S.desktop_inv_all_departments))
    val options = listOf(all) + state.departmentOptions
    ZillitSelect(
        value = options.firstOrNull { it.key == state.departmentKey } ?: all,
        options = options,
        onSelect = { onEvent(CrewDoodEvent.SelectDepartment(it.key)) },
        label = { it.name },
        showInitials = false,
        modifier = Modifier.width(DEPARTMENT_WIDTH),
    )
}

@Composable
private fun GroupLabel(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
    )
}

/**
 * The design's segmented control — two choices in one track.
 *
 * Local rather than a design-system component: the app's segmented
 * control is a tab strip with its own chrome, and this is a toolbar toggle that has to sit
 * in a row of them at label height.
 */
@Composable
private fun <T> DoodSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(SEGMENT_PAD),
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_PAD),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier
                    .clip(ZillitTheme.shapes.medium)
                    .background(if (active) colors.surface else Color.Transparent)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { onSelect(value) }
                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = SEGMENT_HEIGHT_PAD),
            ) {
                ZillitText(
                    text = label,
                    style = ZillitTheme.typography.labelSmall.copy(
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (active) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The web's skeleton: a dozen rows with a bar where the booking would be. */
@Composable
private fun LoadingRows() {
    Column(
        Modifier.fillMaxSize().padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        repeat(SKELETON_ROWS) { index ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitSkeletonBar(Modifier.width(SKELETON_ROLE))
                ZillitSkeletonBar(Modifier.width(SKELETON_NAME))
                Spacer(Modifier.width(SKELETON_STEP * (index % SKELETON_STAGGER)))
                ZillitSkeletonBar(Modifier.weight(1f))
            }
        }
    }
}

private val SWATCH = 12.dp
private val SEARCH_WIDTH = 220.dp
/** The web's own breakpoint for the group labels. */
private val LABELS_FROM = 1280.dp
/** What the bar needs before the trailing controls can hug the right edge. */
private val FITS_WITH_LABELS = 1280.dp
private val FITS_WITHOUT_LABELS = 1100.dp
private val DEPARTMENT_WIDTH = 200.dp
private val SEGMENT_PAD = 3.dp
private val SEGMENT_HEIGHT_PAD = 5.dp
private val SKELETON_ROLE = 140.dp
private val SKELETON_NAME = 120.dp
private val SKELETON_STEP = 24.dp
private const val SKELETON_ROWS = 12
private const val SKELETON_STAGGER = 5

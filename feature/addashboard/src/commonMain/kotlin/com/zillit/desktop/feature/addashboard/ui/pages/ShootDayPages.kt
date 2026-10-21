package com.zillit.desktop.feature.addashboard.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.ArtisteCategory
import com.zillit.desktop.feature.addashboard.domain.ExportFormat
import com.zillit.desktop.feature.addashboard.domain.RateLine
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import com.zillit.desktop.feature.addashboard.ui.AdDestination
import com.zillit.desktop.feature.addashboard.ui.AdEvent
import com.zillit.desktop.feature.addashboard.ui.AdUiState
import com.zillit.desktop.feature.addashboard.ui.AdditionalsEvent
import com.zillit.desktop.feature.addashboard.ui.AddExtrasEvent
import com.zillit.desktop.feature.addashboard.ui.DayEvent

/**
 * Today's List: the live list for the day the dashboard is on.
 *
 * **Mark present** and **Mark out** stamp *now* — there is no time field here,
 * deliberately. A manual arrival time is set on Attendance, and a manual or
 * cleared wrap time in the runner's Wrap step; this page is for the AD walking
 * the line with a tablet.
 */
@Composable
internal fun TodaysListPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    AdPage {
        DayStatStrip(state, onEvent)

        ZillitSectionCard(
            title = str(S.desktop_ad_on_the_call),
            meta = str(S.desktop_ad_n_on_call, state.onToday),
            padded = false,
            action = {
                ZillitSearchField(
                    value = state.todayList.search,
                    onValueChange = { onEvent(DayEvent.Search(it)) },
                    modifier = Modifier.width(SEARCH_WIDTH),
                    placeholder = str(S.desktop_ad_search_the_register),
                )
                if (state.canEditDay) {
                    ZillitButton(
                        text = str(S.desktop_ad_add_extras),
                        onClick = { onEvent(AdEvent.Open(AdDestination.AddExtras)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.UserPlus,
                    )
                }
                ExportMenu(state, onEvent)
            },
        ) {
            ZillitDataTable(
                rows = state.todayRows,
                columns = todayColumns(state, onEvent),
                key = { it.id },
                onRowClick = { onEvent(DayEvent.OpenArtisteRow(it)) },
                emptyTitle = str(S.desktop_ad_nobody_on_day),
                emptyMessage = str(S.desktop_ad_nobody_on_day_message),
                loading = state.loading && state.dayList.isEmpty(),
                // A table inside a scrolling page must not virtualise: the
                // parent is infinitely tall and a virtualised table throws.
                virtualised = false,
            )
        }
    }

    VoucherDialog(state, onEvent)
    ArtisteRowDialog(state, onEvent)
    DaySummaryDialog(state, onEvent)
}

/** The day at a glance, and the one shortcut off it. */
@Composable
private fun DayStatStrip(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitStatTile(
            label = str(S.desktop_ad_on_the_call),
            value = state.onToday.toString(),
            modifier = Modifier.fillMaxWidth().weight(1f),
            icon = ZillitIcons.Users,
        )
        ZillitStatTile(
            label = str(S.desktop_ad_turned_up),
            value = state.presentToday.toString(),
            modifier = Modifier.fillMaxWidth().weight(1f),
            tone = StatusTone.Ready,
            icon = ZillitIcons.Tick,
        )
        ZillitStatTile(
            label = str(S.desktop_ad_day_status_wrapped),
            value = state.wrappedToday.toString(),
            modifier = Modifier.fillMaxWidth().weight(1f),
            tone = StatusTone.Progress,
            icon = ZillitIcons.Clock,
        )
        ZillitStatTile(
            label = str(S.desktop_ad_not_yet_signed),
            value = state.unsignedToday.toString(),
            modifier = Modifier.fillMaxWidth().weight(1f),
            tone = if (state.unsignedToday > 0) StatusTone.Pending else StatusTone.Done,
            icon = ZillitIcons.Signature,
            onClick = { onEvent(DayEvent.OpenSummary) },
        )
    }
}

@Composable
private fun ExportMenu(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ExportFormat.entries.forEach { format ->
            ZillitButton(
                text = format.extension.uppercase(),
                onClick = { onEvent(DayEvent.Export(format)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                loading = state.todayList.exporting,
            )
        }
    }
}

private fun todayColumns(
    state: AdUiState,
    onEvent: (AdEvent) -> Unit,
): List<TableColumn<SupportingArtistDay>> = buildList {
    add(textColumn(str(S.name), width = ColumnWidth.Weight(2f)) { it.artisteName })
    add(textColumn(str(S.av_category)) { it.category.label })
    add(textColumn(str(S.desktop_engagement)) { it.engagement.label })
    add(
        TableColumn(str(S.desktop_ad_time_in), width = ColumnWidth.Fixed(TIME_WIDTH)) { row ->
            TimeCell(
                time = row.callTime,
                enabled = state.canEditDay && !row.isBlocked,
                onMark = { onEvent(DayEvent.MarkPresent(row.id)) },
                markLabel = str(S.desktop_ad_mark_present),
            )
        },
    )
    add(
        TableColumn(str(S.desktop_ad_time_out), width = ColumnWidth.Fixed(TIME_WIDTH)) { row ->
            TimeCell(
                time = row.wrapTime,
                enabled = state.canEditDay && row.isPresent,
                onMark = { onEvent(DayEvent.MarkOut(row.id)) },
                markLabel = str(S.desktop_ad_mark_out),
            )
        },
    )
    add(
        TableColumn(str(S.amount), width = ColumnWidth.Fixed(AMOUNT_WIDTH), numeric = true) { row ->
            ZillitText(
                text = state.money(row.payTotal, row.currency),
                style = ZillitTheme.typography.numeric,
            )
        },
    )
    add(
        TableColumn(str(S.status), width = ColumnWidth.Fixed(STATUS_WIDTH)) { row ->
            ZillitStatusPill(label = row.statusLabel(), tone = row.statusTone())
        },
    )
    add(
        TableColumn(str(S.dd_actions), width = ColumnWidth.Fixed(ACTION_WIDTH)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                // A voucher exists only for a direct-PAYE artiste who wrapped:
                // an agency artiste is paid through their agency's order.
                if (row.hasVoucher) {
                    ZillitIconButton(
                        icon = ZillitIcons.Receipt,
                        contentDescription = str(S.desktop_ad_voucher),
                        onClick = { onEvent(DayEvent.OpenVoucher(row)) },
                    )
                }
                if (state.canEditDay) {
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.remove),
                        onClick = { onEvent(DayEvent.RemoveFromDay(row.id)) },
                    )
                }
            }
        },
    )
}

/** A stamped time, or the button that stamps it. */
@Composable
private fun TimeCell(time: String, enabled: Boolean, markLabel: String, onMark: () -> Unit) {
    if (time.isNotBlank()) {
        ZillitText(text = time, style = ZillitTheme.typography.numeric)
    } else if (enabled) {
        ZillitButton(
            text = markLabel,
            onClick = onMark,
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
    } else {
        ZillitText(
            text = EM_DASH,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/**
 * Add Extras: books artistes onto today, or onto tomorrow.
 *
 * Tomorrow is deliberate — a unit books its crowd the night before — so this
 * page follows the chosen day's own lock rather than today's.
 */
@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
internal fun AddExtrasPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    AdPage {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitChoiceChip(
                label = str(S.today),
                selected = state.addExtras.dayOffset == 0,
                onClick = { onEvent(AddExtrasEvent.PickDay(0)) },
            )
            ZillitChoiceChip(
                label = str(S.tomorrow),
                selected = state.addExtras.dayOffset == 1,
                onClick = { onEvent(AddExtrasEvent.PickDay(1)) },
            )
        }

        if (!state.canEditDay) {
            ZillitEmptyState(
                title = str(S.desktop_ad_day_locked_notice),
                icon = ZillitIcons.Lock,
            )
            return@AdPage
        }

        state.addExtras.error?.let { message ->
            ZillitText(text = message, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger)
        }

        val fresh = state.addExtras.newArtiste
        if (fresh == null) {
            ZillitSectionCard(
                title = str(S.desktop_ad_book_from_register),
                meta = str(S.desktop_ad_n_chosen, state.addExtras.chosen.size),
                padded = false,
                action = {
                    ZillitSearchField(
                        value = state.addExtras.search,
                        onValueChange = { onEvent(AddExtrasEvent.Search(it)) },
                        modifier = Modifier.width(SEARCH_WIDTH),
                    )
                    ZillitButton(
                        text = str(S.desktop_ad_new_external_artiste),
                        onClick = { onEvent(AddExtrasEvent.StartNewArtiste) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.UserPlus,
                    )
                },
            ) {
                val addable = state.addable(state.addExtras.search)
                ZillitDataTable(
                    rows = addable,
                    columns = listOf(
                        TableColumn(str(S.select), width = ColumnWidth.Fixed(CHECK_WIDTH)) { artiste ->
                            ZillitCheckbox(
                                checked = artiste.id in state.addExtras.chosen,
                                onCheckedChange = { onEvent(AddExtrasEvent.Toggle(artiste.id)) },
                            )
                        },
                        textColumn(str(S.name), width = ColumnWidth.Weight(2f)) { it.name },
                        textColumn(str(S.av_category)) { it.category.label },
                        textColumn(str(S.desktop_dm_agency)) { it.agencyName },
                        textColumn(str(S.status)) { it.status.label },
                    ),
                    key = { it.id },
                    emptyTitle = str(S.desktop_ad_everyone_already_on_day),
                    virtualised = false,
                )
            }
        } else {
            ZillitSectionCard(title = str(S.desktop_ad_new_external_artiste)) {
                Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                        ZillitTextField(
                            value = fresh.firstName,
                            onValueChange = { onEvent(AddExtrasEvent.NewFirstName(it)) },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            label = str(S.first_name_label),
                        )
                        ZillitTextField(
                            value = fresh.lastName,
                            onValueChange = { onEvent(AddExtrasEvent.NewLastName(it)) },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            label = str(S.last_name_label),
                        )
                    }
                    ZillitTextField(
                        value = fresh.email,
                        onValueChange = { onEvent(AddExtrasEvent.NewEmail(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = str(S.email),
                        // Required here and nowhere else in this module: the
                        // portal's code can only go to the address on file, so
                        // an artiste booked without one can never approve
                        // their own day.
                        helperText = str(S.desktop_ad_email_needed_for_portal),
                    )
                    ZillitTextField(
                        value = fresh.phone,
                        onValueChange = { onEvent(AddExtrasEvent.NewPhone(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = str(S.phone),
                    )
                    ZillitSelect(
                        value = fresh.category,
                        options = ArtisteCategory.entries,
                        onSelect = { onEvent(AddExtrasEvent.NewCategory(it)) },
                        label = { it.label },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ZillitButton(
                        text = str(S.cancel),
                        onClick = { onEvent(AddExtrasEvent.CancelNewArtiste) },
                        variant = ButtonVariant.Tertiary,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitTextField(
                value = state.addExtras.callTime,
                onValueChange = { onEvent(AddExtrasEvent.CallTime(it)) },
                modifier = Modifier.width(TIME_FIELD_WIDTH),
                label = str(S.desktop_ad_call_time_optional),
                placeholder = "07:30",
            )
            ZillitButton(
                text = str(S.desktop_ad_add_artistes),
                onClick = { onEvent(AddExtrasEvent.Submit) },
                enabled = state.addExtras.ready,
                loading = state.addExtras.saving,
                leadingIcon = ZillitIcons.Add,
            )
        }
    }
}

/**
 * Attendance: time in and day type, and nothing else.
 *
 * No time out at all, which is the web's own split: a wrap is recorded in the
 * runner's Wrap step or with Today's List's **Mark out**.
 */
@Composable
internal fun AttendancePage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    AdPage {
        ZillitSectionCard(
            title = str(S.desktop_ad_attendance),
            meta = str(S.desktop_ad_n_of_m_marked, state.dayList.count { it.isHandled }, state.onToday),
            padded = false,
            action = {
                ZillitSearchField(
                    value = state.attendance.search,
                    onValueChange = { onEvent(DayEvent.Search(it)) },
                    modifier = Modifier.width(SEARCH_WIDTH),
                )
                if (state.canEditDay) {
                    ZillitButton(
                        text = str(S.desktop_ad_mark_all_present),
                        onClick = { onEvent(DayEvent.MarkAllPresent) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        loading = state.runner.bulkBusy,
                    )
                }
            },
        ) {
            ZillitDataTable(
                rows = state.attendanceRows,
                columns = attendanceColumns(state, onEvent),
                key = { it.id },
                emptyTitle = str(S.desktop_ad_nobody_on_day),
                loading = state.loading && state.dayList.isEmpty(),
                virtualised = false,
            )
        }
    }
}

@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
private fun attendanceColumns(
    state: AdUiState,
    onEvent: (AdEvent) -> Unit,
): List<TableColumn<SupportingArtistDay>> = listOf(
    textColumn(str(S.name), width = ColumnWidth.Weight(2f)) { it.artisteName },
    textColumn(str(S.av_category)) { it.category.label },
    TableColumn(str(S.desktop_ad_time_in), width = ColumnWidth.Fixed(TIME_ENTRY_WIDTH)) { row ->
        // The typed time, not "now": Attendance is where a late arrival or a
        // corrected call is entered by hand.
        ZillitTextField(
            value = state.attendance.timeDraft[row.id] ?: row.callTime,
            onValueChange = { onEvent(DayEvent.TimeDraft(row.id, it)) },
            // Committed on leaving the field as well as on Enter: a typed
            // arrival nobody pressed Enter on was otherwise lost on the next
            // refetch.
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focus ->
                    if (!focus.isFocused) {
                        state.attendance.timeDraft[row.id]
                            ?.takeIf { it != row.callTime }
                            ?.let { onEvent(DayEvent.MarkPresentAt(row.id, it)) }
                    }
                },
            placeholder = "07:30",
            enabled = state.canEditDay && !row.isBlocked,
            onImeAction = {
                state.attendance.timeDraft[row.id]?.let { onEvent(DayEvent.MarkPresentAt(row.id, it)) }
            },
        )
    },
    TableColumn(str(S.dm_rule_day_type), width = ColumnWidth.Fixed(DAY_TYPE_WIDTH)) { row ->
        val options = row.dayTypes.map { it.dayType }.ifEmpty { PRESET_DAY_TYPES }
        ZillitSelect(
            value = row.dayType.ifBlank { options.first() },
            options = options,
            onSelect = { onEvent(DayEvent.SetDayType(row.id, it)) },
            label = { it },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canEditDay,
        )
    },
    TableColumn(str(S.dd_actions), width = ColumnWidth.Fixed(ATTENDANCE_ACTION_WIDTH)) { row ->
        if (!state.canEditDay) return@TableColumn
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitButton(
                text = str(S.desktop_call_present),
                onClick = { onEvent(DayEvent.MarkPresent(row.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.desktop_ad_absent),
                onClick = { onEvent(DayEvent.MarkAbsent(row.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            if (row.isHandled) {
                ZillitButton(
                    text = str(S.desktop_ad_unmark),
                    onClick = { onEvent(DayEvent.Unmark(row.id)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
    },
)

/**
 * Additionals: the allowances and fees from each artiste's deal, their notes,
 * and one hand-typed payment each.
 *
 * Three save rules, deliberately different: a tile saves on click, a note
 * saves when it is left and has changed, and the custom payment saves only on
 * its own Save. Nothing here is re-seeded from the server, because the refetch
 * lands about 900 ms after any write and would wipe half-typed text.
 */
@Composable
internal fun AdditionalsPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    AdPage {
        ZillitSearchField(
            value = state.additionals.search,
            onValueChange = { onEvent(AdditionalsEvent.Search(it)) },
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        if (state.additionalsRows.isEmpty()) {
            ZillitEmptyState(title = str(S.desktop_ad_nobody_on_day), icon = ZillitIcons.Users)
            return@AdPage
        }
        state.additionalsRows.forEach { row ->
            AdditionalsRow(state, row, onEvent)
        }
    }
}

@Composable
private fun AdditionalsRow(state: AdUiState, row: SupportingArtistDay, onEvent: (AdEvent) -> Unit) {
    val expanded = row.id in state.additionals.expanded
    ZillitSectionCard(
        title = row.artisteName,
        meta = state.money(row.payTotal, row.currency),
        action = {
            ZillitIconButton(
                icon = if (expanded) ZillitIcons.ChevronUp else ZillitIcons.ChevronDown,
                contentDescription = str(if (expanded) S.desktop_collapse else S.desktop_expand),
                onClick = { onEvent(AdditionalsEvent.Expand(row.id)) },
            )
        },
    ) {
        if (!expanded) {
            // Committed money only: the chips never show a draft, so a
            // half-typed payment cannot read as agreed.
            ZillitText(
                text = str(
                    S.desktop_ad_n_allowances_n_fees,
                    row.allowances.size,
                    row.additionalFees.size,
                ),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            return@ZillitSectionCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            RateTiles(
                label = str(S.allowances_label),
                options = row.dealAllowances,
                chosen = row.allowances,
                enabled = state.canEditDay,
                onToggle = { onEvent(AdditionalsEvent.ToggleAllowance(row.id, it)) },
                symbol = state.currencies.symbolFor(row.currency),
            )
            RateTiles(
                label = str(S.desktop_additional_fees),
                options = row.dealAdditionalFees,
                chosen = row.additionalFees,
                enabled = state.canEditDay,
                onToggle = { onEvent(AdditionalsEvent.ToggleFee(row.id, it)) },
                symbol = state.currencies.symbolFor(row.currency),
            )
            CustomPayment(state, row, onEvent)
            ZillitTextField(
                value = state.additionals.noteDrafts[row.id] ?: row.notes,
                onValueChange = { onEvent(AdditionalsEvent.NoteDraft(row.id, it)) },
                // Saved when the field is **left**, and only when the text has
                // changed — the web's rule. The handler compares against what
                // the server holds, so leaving an untouched field writes
                // nothing.
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focus ->
                        if (!focus.isFocused) onEvent(AdditionalsEvent.SaveNote(row.id))
                    },
                label = str(S.notes),
                singleLine = false,
                enabled = state.canEditDay,
                onImeAction = { onEvent(AdditionalsEvent.SaveNote(row.id)) },
            )
        }
    }
}

@Composable
private fun RateTiles(
    label: String,
    options: List<RateLine>,
    chosen: List<RateLine>,
    enabled: Boolean,
    symbol: String,
    onToggle: (RateLine) -> Unit,
) {
    if (options.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitSectionLabel(text = label)
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            options.forEach { option ->
                val on = chosen.any { it.identifier == option.identifier }
                ZillitChoiceChip(
                    label = "${option.label} · $symbol${option.rateAmount}",
                    selected = on,
                    onClick = { if (enabled) onToggle(option) },
                )
            }
        }
    }
}

/**
 * The one hand-typed payment per artiste-day.
 *
 * An amount above zero upserts it; an amount of zero with one already saved
 * **removes** it, which is the delete gesture; zero with nothing saved does
 * nothing at all.
 */
@Composable
private fun CustomPayment(state: AdUiState, row: SupportingArtistDay, onEvent: (AdEvent) -> Unit) {
    val saved = row.customFee
    val description = state.additionals.customDescription[row.id] ?: saved?.label.orEmpty()
    val amount = state.additionals.customAmount[row.id] ?: saved?.rateAmount?.toString().orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitSectionLabel(text = str(S.desktop_ad_custom_payment))
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitTextField(
                value = description,
                onValueChange = { onEvent(AdditionalsEvent.CustomDescription(row.id, it)) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = str(S.description),
                enabled = state.canEditDay,
            )
            ZillitTextField(
                value = amount,
                onValueChange = { onEvent(AdditionalsEvent.CustomAmount(row.id, it)) },
                modifier = Modifier.width(AMOUNT_FIELD_WIDTH),
                placeholder = "0.00",
                enabled = state.canEditDay,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(AdditionalsEvent.SaveCustom(row.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                // Zero with nothing saved has nothing to do.
                enabled = state.canEditDay &&
                    ((amount.toDoubleOrNull() ?: 0.0) > 0.0 || saved != null),
                loading = row.id in state.additionals.savingCustom,
            )
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(AdditionalsEvent.CancelCustom(row.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

/** The web's three presets, offered when an artiste's deal names none. */
internal val PRESET_DAY_TYPES: List<String> = listOf("SWD", "CWD", "SCWD")

private val SEARCH_WIDTH = 220.dp
private val TIME_WIDTH = 110.dp
private val TIME_ENTRY_WIDTH = 120.dp
private val TIME_FIELD_WIDTH = 140.dp
private val AMOUNT_WIDTH = 110.dp
private val AMOUNT_FIELD_WIDTH = 120.dp
private val STATUS_WIDTH = 120.dp
private val ACTION_WIDTH = 96.dp
private val ATTENDANCE_ACTION_WIDTH = 230.dp
private val DAY_TYPE_WIDTH = 120.dp
private val CHECK_WIDTH = 56.dp

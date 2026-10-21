package com.zillit.desktop.feature.addashboard.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdCrewMember
import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.ReportKind
import com.zillit.desktop.feature.addashboard.ui.AdEvent
import com.zillit.desktop.feature.addashboard.ui.AdUiState
import com.zillit.desktop.feature.addashboard.ui.AgencyEvent
import com.zillit.desktop.feature.addashboard.ui.BankField
import com.zillit.desktop.feature.addashboard.ui.DayEvent
import com.zillit.desktop.feature.addashboard.ui.RatesEvent
import com.zillit.desktop.feature.addashboard.ui.RegisterEvent
import com.zillit.desktop.feature.addashboard.ui.ReportEvent
import com.zillit.desktop.feature.addashboard.ui.RunnerEvent
import com.zillit.desktop.feature.addashboard.ui.SubmitEvent

/** Every dialog the dashboard raises, over whichever page raised it. */

// -- the register ------------------------------------------------------------

@Composable
internal fun DeleteArtisteDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val target = state.register.confirmDelete
    ZillitDialogShell(
        title = str(S.desktop_ad_delete_artiste),
        subtitle = target?.name,
        onDismiss = { onEvent(RegisterEvent.CancelDelete) },
        visible = target != null,
        icon = ZillitIcons.Trash,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(RegisterEvent.CancelDelete) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.delete),
                onClick = { onEvent(RegisterEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
                loading = state.register.deleting,
            )
        },
    ) {
        // The delete cascades: the artiste's rows on every day go with them.
        ZillitText(
            text = str(S.desktop_ad_delete_artiste_warning),
            style = ZillitTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun BlockArtisteDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val open = state.artisteDetail
    ZillitDialogShell(
        title = str(S.desktop_ad_block_title),
        subtitle = open?.artiste?.name,
        onDismiss = { onEvent(RegisterEvent.CancelBlock) },
        visible = open?.confirmBlock == true,
        icon = ZillitIcons.Shield,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(RegisterEvent.CancelBlock) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.block),
                onClick = { onEvent(RegisterEvent.ConfirmBlock) },
                variant = ButtonVariant.Danger,
                loading = open?.busy == true,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(text = str(S.desktop_ad_block_subtitle), style = ZillitTheme.typography.bodySmall)
            ZillitTextField(
                value = open?.blockReason.orEmpty(),
                onValueChange = { onEvent(RegisterEvent.BlockReason(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.reason),
                // Recorded against the artiste, so the next AD knows why.
                helperText = str(S.desktop_ad_recorded_against_artiste),
            )
        }
    }
}

// -- the day -----------------------------------------------------------------

/** The day voucher: what one artiste is owed for one day. */
@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
internal fun VoucherDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val row = state.todayList.openVoucher
    ZillitDialogShell(
        title = str(S.desktop_ad_voucher),
        subtitle = row?.artisteName,
        onDismiss = { onEvent(DayEvent.CloseVoucher) },
        visible = row != null,
        icon = ZillitIcons.Receipt,
        headerTrailing = {
            row?.let {
                ZillitStatusPill(label = it.statusLabel(), tone = it.statusTone())
            }
        },
        actions = {
            // External direct-PAYE artistes are the only ones the server
            // e-mails, and only once the day is submitted.
            if (row?.needsApprovalLink == true && state.viewer.canPost) {
                ZillitButton(
                    text = str(S.desktop_ad_send_link),
                    onClick = { onEvent(DayEvent.SendApprovalLink(row.id)) },
                    variant = ButtonVariant.Secondary,
                    loading = state.todayList.sendingLink == row.id,
                )
            }
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(DayEvent.CloseVoucher) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        if (row == null) return@ZillitDialogShell
        if (state.todayList.voucherLoading) ZillitSpinner()
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            AdDetailRow(label = str(S.date), value = AdDates.formatDayFull(row.shootDate))
            AdDetailRow(label = str(S.av_category), value = row.category.label)
            AdDetailRow(label = str(S.desktop_engagement), value = row.engagement.label)
            AdDetailRow(label = str(S.dm_rule_day_type), value = row.dayType)
            AdDetailRow(label = str(S.desktop_ad_time_in), value = row.callTime)
            AdDetailRow(label = str(S.desktop_ad_time_out), value = row.wrapTime)
            if (row.meals.isNotEmpty()) {
                AdDetailRow(
                    label = str(S.desktop_ad_meals),
                    value = row.meals.joinToString(", ") { "${it.startTime}–${it.endTime}" },
                )
            }
            ZillitDivider()
            AdDetailRow(
                label = str(S.desktop_ad_engine_gross),
                value = state.money(row.gross, row.currency),
            )
            row.allowances.forEach { line ->
                AdDetailRow(label = line.label, value = state.money(line.flatAmount, row.currency))
            }
            row.additionalFees.forEach { line ->
                AdDetailRow(label = line.label, value = state.money(line.flatAmount, row.currency))
            }
            ZillitDivider()
            // The engine's gross plus the day's flat manual lines — the amount
            // every other screen shows.
            AdDetailRow(label = str(S.ah_total_label), value = state.money(row.payTotal, row.currency))
            if (row.signed) {
                AdDetailRow(label = str(S.desktop_ad_signature_method), value = row.signMethod)
            }
        }
    }
}

/** One row on the day, opened from the list. */
@Composable
internal fun ArtisteRowDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val row = state.todayList.openArtiste
    ZillitDialogShell(
        title = row?.artisteName.orEmpty(),
        subtitle = row?.category?.label,
        onDismiss = { onEvent(DayEvent.CloseArtisteRow) },
        visible = row != null,
        icon = ZillitIcons.User,
        actions = {
            // Guarded here too: a control outside the page files still needs
            // the gate, and the web was caught once with this one unguarded.
            if (row != null && state.canEditDay && !row.isPresent) {
                ZillitButton(
                    text = str(S.desktop_ad_confirm_present),
                    onClick = { onEvent(DayEvent.MarkPresent(row.id)) },
                    variant = ButtonVariant.Secondary,
                )
            }
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(DayEvent.CloseArtisteRow) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        if (row == null) return@ZillitDialogShell
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            AdDetailRow(label = str(S.desktop_reference), value = row.refNumber)
            AdDetailRow(label = str(S.desktop_engagement), value = row.engagement.label)
            AdDetailRow(label = str(S.desktop_dm_agency), value = row.agencyName)
            AdDetailRow(label = str(S.dm_label_union), value = row.union)
            AdDetailRow(label = str(S.desktop_ad_time_in), value = row.callTime)
            AdDetailRow(label = str(S.desktop_ad_time_out), value = row.wrapTime)
            AdDetailRow(label = str(S.amount), value = state.money(row.payTotal, row.currency))
            if (row.blockReason.isNotBlank()) {
                AdDetailRow(label = str(S.reason), value = row.blockReason)
            }
        }
    }
}

/**
 * Day Report & Vouchers: a local summary of the day.
 *
 * Not the report the distribution list receives — that is a published Wrap, AD
 * or Exhibit G report. This is the AD's own count, and its "signed" tally
 * includes submitted rows, as the web's does.
 */
@Composable
internal fun DaySummaryDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_ad_day_report_and_vouchers),
        subtitle = AdDates.formatDayFull(state.shootDate),
        onDismiss = { onEvent(DayEvent.CloseSummary) },
        visible = state.todayList.summaryOpen,
        icon = ZillitIcons.Receipt,
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(DayEvent.CloseSummary) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            AdDetailRow(label = str(S.desktop_ad_on_the_call), value = state.onToday.toString())
            AdDetailRow(label = str(S.desktop_ad_turned_up), value = state.presentToday.toString())
            AdDetailRow(label = str(S.desktop_ad_day_status_wrapped), value = state.wrappedToday.toString())
            AdDetailRow(
                label = str(S.signed),
                value = state.dayList.count { it.signed || it.isSubmitted }.toString(),
            )
            ZillitDivider()
            ZillitSectionLabel(text = str(S.desktop_ad_by_currency))
            state.dayTotals.forEach { totals ->
                AdDetailRow(
                    label = totals.currency.ifBlank { state.currencies.defaultCode },
                    value = state.money(totals.total, totals.currency),
                )
            }
        }
    }
}

/** One artiste's own meal breaks, which are not the day's. */
@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
internal fun ArtisteMealsDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val id = state.runner.openArtisteMeals
    val row = state.dayList.firstOrNull { it.id == id }
    val meals = id?.let { state.runner.artisteMeals[it] } ?: row?.meals.orEmpty()
    ZillitDialogShell(
        title = str(S.desktop_ad_per_artiste_meals),
        subtitle = row?.artisteName,
        onDismiss = { onEvent(RunnerEvent.CloseArtisteMeals) },
        visible = id != null,
        icon = ZillitIcons.Clock,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(RunnerEvent.CloseArtisteMeals) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { id?.let { onEvent(RunnerEvent.SaveArtisteMeals(it)) } },
                enabled = state.canEditDay,
            )
        },
    ) {
        if (id == null) return@ZillitDialogShell
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            meals.forEachIndexed { index, meal ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitTextField(
                        value = meal.startTime,
                        onValueChange = { onEvent(RunnerEvent.ArtisteMealStart(id, index, it)) },
                        modifier = Modifier.width(DIALOG_TIME_WIDTH),
                        label = str(S.desktop_ad_meal_start),
                        placeholder = "13:00",
                    )
                    ZillitTextField(
                        value = meal.endTime,
                        onValueChange = { onEvent(RunnerEvent.ArtisteMealEnd(id, index, it)) },
                        modifier = Modifier.width(DIALOG_TIME_WIDTH),
                        label = str(S.desktop_ad_meal_end),
                        placeholder = "13:30",
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.remove),
                        onClick = { onEvent(RunnerEvent.RemoveArtisteMeal(id, index)) },
                    )
                }
            }
            ZillitButton(
                text = str(S.desktop_ad_add_meal),
                onClick = { onEvent(RunnerEvent.AddArtisteMeal(id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }
    }
}

/**
 * The AD's signature on the day.
 *
 * Drawn here, or typed when there is no pen; either way the host renders it to
 * a PNG, because what the server stores is printed on every voucher. A
 * signature already in the AD's library can be picked instead of drawing a new
 * one.
 */
@Composable
internal fun SignaturePadDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_ad_add_signature),
        onDismiss = { onEvent(SubmitEvent.CloseSignaturePad) },
        visible = state.submit.signaturePadOpen,
        icon = ZillitIcons.Signature,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(SubmitEvent.CloseSignaturePad) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.sign),
                onClick = {
                    onEvent(SubmitEvent.RenderSignature(SIGNATURE_WIDTH_PX, SIGNATURE_HEIGHT_PX))
                },
                enabled = state.submit.padHasInk,
                loading = state.submit.rendering,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            AdSignaturePad(
                strokes = state.submit.strokes,
                typedName = state.submit.typedName,
                busy = state.submit.rendering,
                onStroke = { onEvent(SubmitEvent.Stroke(it)) },
                onTypedName = { onEvent(SubmitEvent.TypedName(it)) },
                onClear = { onEvent(SubmitEvent.ClearPad) },
            )
            if (state.submit.savedSignatures.isNotEmpty()) {
                ZillitSectionLabel(text = str(S.desktop_ad_saved_signatures))
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    state.submit.savedSignatures.forEach { saved ->
                        ZillitChoiceChip(
                            label = saved.name.ifBlank { str(S.signature_txt) },
                            selected = state.submit.pickedSignature == saved.id,
                            onClick = { onEvent(SubmitEvent.PickSaved(saved.id)) },
                        )
                    }
                }
            }
        }
    }
}

// -- agencies ----------------------------------------------------------------

@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
internal fun AgencyFormDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val form = state.agency.form
    ZillitDialogShell(
        title = if (form?.agencyId == null) str(S.desktop_ad_add_agency) else str(S.desktop_ad_edit_agency),
        onDismiss = { onEvent(AgencyEvent.CancelForm) },
        visible = form != null,
        icon = ZillitIcons.Building,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(AgencyEvent.CancelForm) },
                variant = ButtonVariant.Tertiary,
            )
            // Verify is a second call, as it is on the web: the save route
            // takes no verified flag.
            if (form?.agencyId != null) {
                ZillitButton(
                    text = str(S.desktop_ad_save_and_verify),
                    onClick = { onEvent(AgencyEvent.Save(andVerify = true)) },
                    variant = ButtonVariant.Secondary,
                    loading = form.saving,
                )
            }
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(AgencyEvent.Save(andVerify = false)) },
                loading = form?.saving == true,
            )
        },
    ) {
        if (form == null) return@ZillitDialogShell
        form.error?.let { message ->
            ZillitNotice(
                text = message,
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = form.draft.name,
                onValueChange = { onEvent(AgencyEvent.Name(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.name),
            )
            ZillitTextField(
                value = form.draft.contactPerson,
                onValueChange = { onEvent(AgencyEvent.Contact(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.contact),
            )
            ZillitTextField(
                value = form.draft.email,
                onValueChange = { onEvent(AgencyEvent.Email(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.email),
            )
            ZillitTextField(
                value = form.draft.phone,
                onValueChange = { onEvent(AgencyEvent.Phone(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.phone),
            )
            ZillitTextField(
                value = form.draft.address,
                onValueChange = { onEvent(AgencyEvent.Address(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.address),
            )
            ZillitSectionLabel(text = str(S.dm_checklist_bank))
            ZillitTextField(
                value = form.draft.bank.accountName,
                onValueChange = { onEvent(AgencyEvent.Bank(BankField.AccountName, it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.desktop_account_name),
            )
            ZillitTextField(
                value = form.draft.bank.accountNumber,
                onValueChange = { onEvent(AgencyEvent.Bank(BankField.AccountNumber, it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.desktop_ad_account_number),
            )
            ZillitTextField(
                value = form.draft.bank.ibanNumber,
                onValueChange = { onEvent(AgencyEvent.Bank(BankField.Iban, it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.ah_lbl_iban_row),
            )
        }
    }
}

@Composable
internal fun AgencyDetailDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val agency = state.agency.open
    ZillitDialogShell(
        title = agency?.name.orEmpty(),
        onDismiss = { onEvent(AgencyEvent.CloseAgency) },
        visible = agency != null,
        icon = ZillitIcons.Building,
        headerTrailing = {
            agency?.let {
                ZillitStatusPill(
                    label = if (it.verified) str(S.ah_verified) else str(S.pending),
                    tone = if (it.verified) StatusTone.Done else StatusTone.Pending,
                )
            }
        },
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(AgencyEvent.CloseAgency) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        if (agency == null) return@ZillitDialogShell
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            AdDetailRow(label = str(S.contact), value = agency.contactPerson)
            AdDetailRow(label = str(S.email), value = agency.email)
            AdDetailRow(label = str(S.phone), value = agency.phone)
            AdDetailRow(label = str(S.address), value = agency.address)
            AdDetailRow(label = str(S.desktop_account_name), value = agency.bank.accountName)
            AdDetailRow(label = str(S.desktop_ad_account_number), value = agency.bank.accountNumber)
            AdDetailRow(label = str(S.ah_lbl_iban_row), value = agency.bank.ibanNumber)
        }
    }
}

@Composable
internal fun AgencyHistoryDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val agency = state.agency.historyFor
    ZillitDialogShell(
        title = str(S.history),
        subtitle = agency?.name,
        onDismiss = { onEvent(AgencyEvent.CloseHistory) },
        visible = agency != null,
        icon = ZillitIcons.Clock,
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(AgencyEvent.CloseHistory) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        if (state.agency.historyLoading) ZillitSpinner()
        ZillitDataTable(
            rows = state.agency.history,
            columns = listOf(
                textColumn(str(S.date), width = ColumnWidth.Fixed(HISTORY_DATE_WIDTH)) {
                    AdDates.formatInstant(it.at)
                },
                textColumn(str(S.txt_action)) { it.action },
                textColumn(str(S.cr_meta_by)) { it.byName },
            ),
            key = { it.id + it.action },
            emptyTitle = str(S.desktop_ad_no_history),
            virtualised = false,
        )
    }
}

@Composable
internal fun DeleteAgencyDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val target = state.agency.confirmDelete
    ZillitDialogShell(
        title = str(S.desktop_ad_delete_agency),
        subtitle = target?.name,
        onDismiss = { onEvent(AgencyEvent.CancelDelete) },
        visible = target != null,
        icon = ZillitIcons.Trash,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(AgencyEvent.CancelDelete) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.delete),
                onClick = { onEvent(AgencyEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
                loading = state.agency.busy,
            )
        },
    ) {
        ZillitText(text = str(S.desktop_ad_delete_agency_warning), style = ZillitTheme.typography.bodySmall)
    }
}

// -- Rates & Config ----------------------------------------------------------

/** Who the server e-mails a published report's PDF to. */
@Composable
internal fun DistributionPickerDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val crew = state.rates.let { _ -> emptyList<AdCrewMember>() }
    ZillitDialogShell(
        title = str(S.desktop_ad_choose_recipients),
        onDismiss = { onEvent(RatesEvent.CloseDistributionPicker) },
        visible = state.rates.distributionPickerOpen,
        icon = ZillitIcons.Mail,
        actions = {
            ZillitButton(
                text = str(S.ah_done),
                onClick = { onEvent(RatesEvent.CloseDistributionPicker) },
            )
        },
    ) {
        ZillitMultiSelect(
            selected = state.rates.nonUnion.distribution,
            options = state.rates.nonUnion.distribution,
            label = { it.name.ifBlank { it.email } },
            onChange = { picked ->
                // The toggle is per row, so the diff is applied one entry at a
                // time rather than replacing the list wholesale.
                val removed = state.rates.nonUnion.distribution.filterNot { it in picked }
                removed.forEach { onEvent(RatesEvent.ToggleDistribution(it)) }
            },
            modifier = Modifier.fillMaxWidth(),
            showDone = true,
        )
        ZillitText(
            text = str(S.desktop_ad_distribution_saved_with_page),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        if (crew.isNotEmpty()) ZillitDivider()
    }
}

@Suppress("LongMethod") // One form, laid out once.
@Composable
internal fun VendorFormDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val form = state.rates.vendorForm
    ZillitDialogShell(
        title = str(S.desktop_ad_add_agency),
        // Registering it here is what gives its artistes an `agency_id`, and
        // an artiste without one gets no purchase order when the day is
        // submitted.
        subtitle = str(S.desktop_ad_agency_needed_for_po),
        onDismiss = { onEvent(RatesEvent.CancelVendorForm) },
        visible = form != null,
        icon = ZillitIcons.Building,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(RatesEvent.CancelVendorForm) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.desktop_ad_save_and_add_to_list),
                onClick = { onEvent(RatesEvent.SaveVendor) },
                loading = form?.saving == true,
            )
        },
    ) {
        if (form == null) return@ZillitDialogShell
        form.error?.let { message ->
            ZillitNotice(
                text = message,
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = form.draft.name,
                onValueChange = { onEvent(RatesEvent.VendorName(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.name),
            )
            ZillitTextField(
                value = form.draft.contactPerson,
                onValueChange = { onEvent(RatesEvent.VendorContact(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.contact),
            )
            ZillitTextField(
                value = form.draft.email,
                onValueChange = { onEvent(RatesEvent.VendorEmail(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.email),
            )
            ZillitTextField(
                value = form.draft.phone,
                onValueChange = { onEvent(RatesEvent.VendorPhone(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.phone),
            )
            ZillitTextField(
                value = form.draft.address,
                onValueChange = { onEvent(RatesEvent.VendorAddress(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.address),
            )
            ZillitSectionLabel(text = str(S.dm_checklist_bank))
            ZillitTextField(
                value = form.draft.bank.accountName,
                onValueChange = { onEvent(RatesEvent.VendorBank(BankField.AccountName, it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.desktop_account_name),
            )
            ZillitTextField(
                value = form.draft.bank.accountNumber,
                onValueChange = { onEvent(RatesEvent.VendorBank(BankField.AccountNumber, it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.desktop_ad_account_number),
            )
            ZillitTextField(
                value = form.draft.bank.ibanNumber,
                onValueChange = { onEvent(RatesEvent.VendorBank(BankField.Iban, it)) },
                modifier = Modifier.fillMaxWidth(),
                label = str(S.ah_lbl_iban_row),
            )
        }
    }
}

@Composable
internal fun DeleteVendorDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val target = state.rates.confirmDeleteVendor
    ZillitDialogShell(
        title = str(S.desktop_ad_remove_agency),
        subtitle = target?.name,
        onDismiss = { onEvent(RatesEvent.CancelDeleteVendor) },
        visible = target != null,
        icon = ZillitIcons.Trash,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(RatesEvent.CancelDeleteVendor) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.remove),
                onClick = { onEvent(RatesEvent.ConfirmDeleteVendor) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(text = str(S.desktop_ad_remove_agency_warning), style = ZillitTheme.typography.bodySmall)
    }
}

@Composable
internal fun DeleteUnionDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val target = state.rates.confirmDeleteUnion
    ZillitDialogShell(
        title = str(S.desktop_ad_delete_union_breakdown),
        subtitle = target?.label,
        onDismiss = { onEvent(RatesEvent.CancelDeleteUnion) },
        visible = target != null,
        icon = ZillitIcons.Trash,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(RatesEvent.CancelDeleteUnion) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.delete),
                onClick = { onEvent(RatesEvent.ConfirmDeleteUnion) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_ad_delete_union_warning),
            style = ZillitTheme.typography.bodySmall,
        )
    }
}

// -- reports -----------------------------------------------------------------

@Composable
internal fun TemplatePickerDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.dd_pick_a_template),
        onDismiss = { onEvent(ReportEvent.CloseTemplatePicker) },
        visible = state.reports.templatePickerOpen,
        icon = ZillitIcons.Grid,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(ReportEvent.CloseTemplatePicker) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        val offered = if (state.destination == com.zillit.desktop.feature.addashboard.ui.AdDestination.ExhibitG) {
            state.reports.templates.filter { it.kind == ReportKind.ExhibitG }
        } else {
            // Exhibit G is deliberately kept out of the Wrap/AD picker: its
            // layout is statutory and a wrap layout cannot become one.
            state.reports.templates.filter { it.schedulePickable }
        }
        ZillitDataTable(
            rows = offered,
            columns = listOf(
                textColumn(str(S.name), width = ColumnWidth.Weight(2f)) { it.name },
                textColumn(str(S.desktop_csync_field_kind)) { it.kind.label },
                textColumn(str(S.desktop_ad_origin)) { it.originLabel },
                TableColumn(str(S.dd_actions), width = ColumnWidth.Fixed(USE_WIDTH)) { template ->
                    ZillitButton(
                        text = str(S.dm_template_use),
                        onClick = {
                            onEvent(ReportEvent.CloseTemplatePicker)
                            onEvent(
                                ReportEvent.StartFromTemplate(
                                    template,
                                    state.reports.dayLink ?: state.shootDate,
                                ),
                            )
                        },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                },
            ),
            key = { it.id },
            emptyTitle = str(S.desktop_ds_no_templates),
            virtualised = false,
        )
    }
}

@Composable
internal fun DeleteReportDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val target = state.reports.confirmDelete
    ZillitDialogShell(
        title = str(S.desktop_delete_report),
        subtitle = target?.name,
        onDismiss = { onEvent(ReportEvent.CancelDelete) },
        visible = target != null,
        icon = ZillitIcons.Trash,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(ReportEvent.CancelDelete) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.delete),
                onClick = { onEvent(ReportEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
                loading = state.reports.busy,
            )
        },
    ) {
        ZillitText(text = str(S.desktop_ad_delete_report_warning), style = ZillitTheme.typography.bodySmall)
    }
}

/**
 * Publishing: freeze, render the PDF, e-mail the distribution list.
 *
 * The recipients are the **server's** to resolve from Rates & Config —
 * anything the client sends is ignored, so this only previews them.
 */
@Composable
internal fun PublishReportDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val editor = state.reports.editor
    ZillitDialogShell(
        title = str(S.publish),
        subtitle = editor?.name,
        onDismiss = { onEvent(ReportEvent.ClosePublish) },
        visible = editor?.publishOpen == true,
        icon = ZillitIcons.Send,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(ReportEvent.ClosePublish) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.publish),
                onClick = { onEvent(ReportEvent.Publish) },
                loading = editor?.posting == true,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(text = str(S.desktop_ad_publish_warning), style = ZillitTheme.typography.bodySmall)
            ZillitSectionLabel(text = str(S.recipients))
            if (state.rates.nonUnion.distribution.isEmpty()) {
                ZillitText(
                    text = str(S.desktop_ad_no_recipients_configured),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            state.rates.nonUnion.distribution.forEach { entry ->
                AdDetailRow(label = entry.name.ifBlank { str(S.unkone_user) }, value = entry.email)
            }
        }
    }
}

@Composable
internal fun SaveAsTemplateDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val editor = state.reports.editor
    ZillitDialogShell(
        title = str(S.save_as_template),
        onDismiss = { onEvent(ReportEvent.CloseSaveAsTemplate) },
        visible = editor?.saveAsTemplateOpen == true,
        icon = ZillitIcons.Grid,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(ReportEvent.CloseSaveAsTemplate) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(ReportEvent.SaveAsTemplate) },
                enabled = editor?.templateName?.isNotBlank() == true,
            )
        },
    ) {
        ZillitTextField(
            value = editor?.templateName.orEmpty(),
            onValueChange = { onEvent(ReportEvent.TemplateName(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = str(S.name),
        )
    }
}

/**
 * One performer's signature, captured here on the AD's device.
 *
 * Recorded as **witnessed** rather than authenticated: the person signing is
 * not the person signed in. The dialog stays open until the server has it, so
 * a failed call keeps the drawing for a retry rather than losing it.
 */
@Composable
internal fun SignRowDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val editor = state.reports.editor
    val row = editor?.signingRow
    ZillitDialogShell(
        title = str(S.desktop_ad_sign_here),
        subtitle = row?.name,
        onDismiss = { onEvent(ReportEvent.CancelSigning) },
        visible = row != null,
        icon = ZillitIcons.Signature,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(ReportEvent.CancelSigning) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.sign),
                onClick = {
                    onEvent(ReportEvent.RenderRowSignature(SIGNATURE_WIDTH_PX, SIGNATURE_HEIGHT_PX))
                },
                enabled = editor != null &&
                    (editor.signStrokes.any { it.size > 1 } || editor.signTypedName.isNotBlank()),
            )
        },
    ) {
        if (editor == null) return@ZillitDialogShell
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitText(text = str(S.desktop_ad_in_person_note), style = ZillitTheme.typography.bodySmall)
            AdSignaturePad(
                strokes = editor.signStrokes,
                typedName = editor.signTypedName,
                busy = false,
                onStroke = { onEvent(ReportEvent.SignStroke(it)) },
                onTypedName = { onEvent(ReportEvent.SignTypedName(it)) },
                onClear = { onEvent(ReportEvent.ClearSignPad) },
            )
        }
    }
}

@Composable
internal fun ResetSignaturesDialog(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val editor = state.reports.editor
    ZillitDialogShell(
        title = str(S.desktop_ad_reset_signatures),
        onDismiss = { onEvent(ReportEvent.CancelResetSignatures) },
        visible = editor?.confirmReset == true,
        icon = ZillitIcons.Warning,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(ReportEvent.CancelResetSignatures) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.reset),
                onClick = { onEvent(ReportEvent.ConfirmResetSignatures) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        // A reset bumps the signing round, which kills every link already sent.
        ZillitText(
            text = str(S.desktop_ad_reset_signatures_warning),
            style = ZillitTheme.typography.bodySmall,
        )
    }
}

private val DIALOG_TIME_WIDTH = 130.dp
private val HISTORY_DATE_WIDTH = 160.dp
private val USE_WIDTH = 100.dp

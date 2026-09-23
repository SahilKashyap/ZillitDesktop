// One composable per worksheet dialog.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.costreport.ui.worksheet

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.CrDates
import com.zillit.desktop.feature.costreport.domain.EtcVersion
import com.zillit.desktop.feature.costreport.domain.ExportFormat
import com.zillit.desktop.feature.costreport.domain.PostCadence
import com.zillit.desktop.feature.costreport.ui.CrPalette

/** Every worksheet dialog, each driven by [WorksheetUiState.modal]. */
@Composable
internal fun BoxScope.WorksheetDialogs(
    state: WorksheetUiState,
    onEvent: (WorksheetEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val modal = state.modal
    val close = { onEvent(WorksheetEvent.CloseModal) }
    LockDialog(state, modal as? WorksheetModal.Lock, onEvent, close)
    ExportDialog(state, modal as? WorksheetModal.Export, onEvent, close)
    SaveVersionDialog(state, modal as? WorksheetModal.SaveVersion, onEvent, close)
    PublishDialog(state, modal as? WorksheetModal.Publish, onEvent, close)
    OveragesDialog(state, modal == WorksheetModal.Overages, onEvent, close)
    HistoryPanel(state, modal == WorksheetModal.History, onEvent, resolveUser, close)
}

// -- lock ------------------------------------------------------------------------------------

@Composable
private fun LockDialog(
    state: WorksheetUiState,
    modal: WorksheetModal.Lock?,
    onEvent: (WorksheetEvent) -> Unit,
    close: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val week = state.ws.week
    ZillitDialogShell(
        title = str(S.desktop_cr_lock_title),
        subtitle = str(S.desktop_cr_lock_subtitle, week?.label.orEmpty()),
        icon = ZillitIcons.Shield,
        onDismiss = close,
        visible = modal != null,
        width = 540.dp,
        actions = {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZillitIcon(ZillitIcons.Info, tint = colors.textMuted, size = 13.dp)
                ZillitText(
                    str(S.desktop_cr_lock_irreversible),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            ZillitButton(text = str(S.cancel), onClick = close, variant = ButtonVariant.Secondary)
            ZillitButton(
                text = str(S.desktop_lock),
                onClick = { onEvent(WorksheetEvent.ConfirmLock) },
                variant = ButtonVariant.Danger,
                leadingIcon = ZillitIcons.Shield,
            )
        },
    ) {
        ZillitText(
            str(S.desktop_cr_lock_explainer),
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textMuted,
        )
        SummaryTable(
            listOf(
                str(S.cr_meta_period) to week?.label.orEmpty(),
                str(S.range) to week?.range.orEmpty(),
                str(S.desktop_cr_budget_version) to
                    (state.reference.budget(state.ws.applied.budgetKey)?.display ?: "—"),
            ),
        )
        ZillitTextField(
            value = modal?.note.orEmpty(),
            onValueChange = { onEvent(WorksheetEvent.SetLockNote(it)) },
            label = str(S.dm_rule_note_hint),
            placeholder = str(S.desktop_cr_lock_reason_hint),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitNotice(
            text = str(S.desktop_cr_lock_warning),
            tone = StatusTone.Pending,
            icon = ZillitIcons.Warning,
        )
    }
}

// -- export ----------------------------------------------------------------------------------

private data class FormatCard(
    val format: ExportFormat,
    val abbreviation: String,
    private val nameKey: String,
    private val descriptionKey: String,
    val colors: List<Color>,
) {
    val name: String get() = str(nameKey)

    val description: String get() = str(descriptionKey)
}

private val FORMAT_CARDS = listOf(
    FormatCard(
        format = ExportFormat.Pdf,
        abbreviation = "PDF",
        nameKey = S.desktop_cr_full_cost_report,
        descriptionKey = S.desktop_cr_full_cost_report_desc,
        colors = listOf(Color(0xFFFF7A59), Color(0xFFE23B3B)),
    ),
    FormatCard(
        format = ExportFormat.Xlsx,
        abbreviation = "XLSX",
        nameKey = S.desktop_cr_working_report,
        descriptionKey = S.desktop_cr_working_report_desc,
        colors = listOf(Color(0xFF34C97A), Color(0xFF138A52)),
    ),
    FormatCard(
        format = ExportFormat.Csv,
        abbreviation = "CSV",
        nameKey = S.desktop_cr_raw_export,
        descriptionKey = S.desktop_cr_raw_export_desc,
        colors = listOf(Color(0xFF6AA3FF), Color(0xFF2862E0)),
    ),
)

@Composable
private fun ExportDialog(
    state: WorksheetUiState,
    modal: WorksheetModal.Export?,
    onEvent: (WorksheetEvent) -> Unit,
    close: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val week = state.ws.week
    ZillitDialogShell(
        title = str(S.desktop_cr_export_title),
        subtitle = str(S.desktop_cr_export_subtitle, week?.label.orEmpty()),
        icon = ZillitIcons.Download,
        onDismiss = close,
        visible = modal != null,
        width = 540.dp,
        actions = {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZillitIcon(ZillitIcons.Info, tint = colors.textMuted, size = 13.dp)
                ZillitText(
                    str(S.desktop_cr_download_starts),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            ZillitButton(text = str(S.cancel), onClick = close, variant = ButtonVariant.Secondary)
            ZillitButton(
                text = str(S.desktop_cr_export_and_download),
                onClick = { onEvent(WorksheetEvent.ConfirmExport) },
                leadingIcon = ZillitIcons.Download,
            )
        },
    ) {
        SectionCaption(str(S.desktop_cr_choose_format))
        FORMAT_CARDS.forEach { card ->
            val selected = modal?.format == card.format
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (selected) CrPalette.CTA.copy(alpha = 0.08f) else colors.surface)
                    .border(
                        1.5.dp,
                        if (selected) CrPalette.CTA.copy(alpha = 0.45f) else colors.border,
                        RoundedCornerShape(13.dp),
                    )
                    .clickable { onEvent(WorksheetEvent.SetExportFormat(card.format)) }
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Box(
                    modifier = Modifier.size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(card.colors)),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitText(
                        card.abbreviation,
                        style = ZillitTheme.typography.numeric.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                    )
                }
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        "${card.abbreviation} — ${card.name}",
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    ZillitText(card.description, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                }
                Box(
                    modifier = Modifier
                        .size(19.dp)
                        .clip(CircleShape)
                        .background(if (selected) CrPalette.ANALYTICS else colors.surface)
                        .border(1.5.dp, if (selected) CrPalette.ANALYTICS else colors.border, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) ZillitIcon(ZillitIcons.Check, tint = Color.White, size = 11.dp)
                }
            }
        }
        SummaryTable(
            rows = listOf(
                str(S.desktop_cr_budget_version) to
                    (state.reference.budget(state.ws.applied.budgetKey)?.display ?: "—"),
                str(S.cr_meta_period) to week?.label.orEmpty(),
                str(S.desktop_cr_generated_by) to (state.generatedBy ?: "—"),
            ),
            trailing = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(str(S.status), style = ZillitTheme.typography.label.copy(
                        fontWeight = FontWeight.SemiBold,
                    ), color = colors.textMuted,
                        modifier = Modifier.weight(1f))
                    if (state.isLocked) {
                        ZillitStatusPill(label = str(S.docusign_prop_locked), tone = StatusTone.Neutral)
                    } else {
                        ZillitStatusPill(label = str(S.recce_open), tone = StatusTone.Done, dot = true)
                    }
                }
            },
        )
    }
}

// -- save version --------------------------------------------------------------------------------

@Composable
private fun SaveVersionDialog(
    state: WorksheetUiState,
    modal: WorksheetModal.SaveVersion?,
    onEvent: (WorksheetEvent) -> Unit,
    close: () -> Unit,
) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = str(S.desktop_cr_save_version_title),
        onDismiss = close,
        visible = modal != null,
        width = 380.dp,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = close,
                variant = ButtonVariant.Secondary,
                enabled = !state.savingVersion,
            )
            ZillitButton(
                text = if (state.savingVersion) str(S.ah_saving) else str(S.desktop_cr_save_version_title),
                onClick = { onEvent(WorksheetEvent.ConfirmSaveVersion) },
                loading = state.savingVersion,
            )
        },
    ) {
        ZillitText(
            str(S.desktop_cr_save_version_explainer),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        ZillitTextField(
            value = modal?.label.orEmpty(),
            onValueChange = { onEvent(WorksheetEvent.SetVersionLabel(it)) },
            label = str(S.ah_lbl_title),
            placeholder = str(S.desktop_cr_save_version_hint),
            enabled = !state.savingVersion,
            onImeAction = { onEvent(WorksheetEvent.ConfirmSaveVersion) },
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// -- publish ------------------------------------------------------------------------------------

private val ALL_COMPANIES: CrCompany
    get() = CrCompany(id = "", name = str(S.desktop_cr_all_companies_consolidated))
private val LIVE_BUDGET: BudgetVersion
    get() = BudgetVersion(id = "", version = "", label = str(S.desktop_cr_live_current), status = "")
private val NO_VERSION: EtcVersion get() = EtcVersion(id = "", label = str(S.desktop_cr_none_no_etc))

@Composable
private fun PublishDialog(
    state: WorksheetUiState,
    modal: WorksheetModal.Publish?,
    onEvent: (WorksheetEvent) -> Unit,
    close: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val form = modal?.form ?: PublishForm()
    val edit = { next: PublishForm -> onEvent(WorksheetEvent.EditPublish(next)) }
    val reference = state.reference
    ZillitDialogShell(
        title = str(S.desktop_cr_publish_title),
        onDismiss = close,
        visible = modal != null,
        width = 480.dp,
        actions = {
            ZillitButton(text = str(S.cancel), onClick = close, variant = ButtonVariant.Secondary)
            ZillitButton(
                text = if (state.posting != null) str(S.desktop_publishing) else str(S.desktop_cr_publish_cr),
                onClick = { onEvent(WorksheetEvent.ConfirmPublish) },
                loading = state.posting != null,
            )
        },
    ) {
        ZillitText(
            str(S.desktop_cr_publish_explainer),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        Field(str(S.cr_meta_cadence)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PostCadence.entries.forEach { cadence ->
                    val active = form.cadence == cadence
                    ZillitText(
                        text = cadence.label,
                        style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) CrPalette.CTA.copy(alpha = 0.10f) else colors.surface)
                            .border(1.dp, if (active) CrPalette.CTA else colors.border, RoundedCornerShape(6.dp))
                            .clickable { edit(form.copy(cadence = cadence)) }
                            .padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        Field(str(S.company)) {
            ZillitSelect(
                value = reference.companies.firstOrNull { it.id == form.companyId } ?: ALL_COMPANIES,
                options = listOf(ALL_COMPANIES) + reference.companies,
                onSelect = { edit(form.copy(companyId = it.id.ifBlank { null })) },
                label = { it.name.ifBlank { it.id } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Field(str(S.desktop_cr_budget_version)) {
            ZillitSelect(
                value = reference.budget(form.budgetKey) ?: LIVE_BUDGET,
                options = listOf(LIVE_BUDGET) + reference.budgets,
                onSelect = { edit(form.copy(budgetKey = it.version.ifBlank { null })) },
                label = { it.display },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Field(str(S.asset_currency)) {
            val choices = reference.currencyChoices
            if (choices.isNotEmpty()) {
                ZillitSelect(
                    value = choices.firstOrNull { it.code == form.currency } ?: choices.first(),
                    options = choices,
                    onSelect = { edit(form.copy(currency = it.code)) },
                    label = { c: CrCurrency -> "${c.code} — ${c.symbol.ifBlank { reference.symbolFor(c.code) }}" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Field(str(S.desktop_cr_cr_version)) {
            ZillitSelect(
                value = form.versions.firstOrNull { it.id == form.etcVersionId } ?: NO_VERSION,
                options = listOf(NO_VERSION) + form.versions,
                onSelect = { edit(form.copy(etcVersionId = it.id.ifBlank { null })) },
                label = { if (it.id.isBlank()) it.label else it.postOptionLabel },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (form.cadence == PostCadence.Custom) {
            Field(str(S.desktop_cr_period_custom)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZillitDateField(
                        value = form.startDate,
                        onValueChange = { edit(form.copy(startDate = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    ZillitDateField(
                        value = form.endDate,
                        onValueChange = { edit(form.copy(endDate = it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            ZillitText(
                str(S.desktop_cr_end_not_later_default),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
        Field(str(S.desktop_cr_post_note_optional)) {
            ZillitTextField(
                value = form.note,
                onValueChange = { edit(form.copy(note = it)) },
                placeholder = str(S.desktop_cr_post_note_hint),
                singleLine = false,
                modifier = Modifier.fillMaxWidth().height(84.dp),
            )
        }
    }
}

// -- overages --------------------------------------------------------------------------------------

@Composable
private fun OveragesDialog(
    state: WorksheetUiState,
    visible: Boolean,
    onEvent: (WorksheetEvent) -> Unit,
    close: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val symbol = state.symbolFor(state.ws)
    val rows = state.overages
    ZillitDialogShell(
        title = str(
            S.desktop_cr_over_budget_title,
            state.ws.week?.let { str(S.desktop_cr_week_number, it.number) }.orEmpty(),
        ),
        icon = ZillitIcons.Warning,
        onDismiss = close,
        visible = visible,
        width = 560.dp,
        actions = {
            ZillitButton(text = str(S.close), onClick = close, variant = ButtonVariant.Secondary)
            ZillitButton(text = str(S.desktop_cr_go_to_live_cr), onClick = { onEvent(WorksheetEvent.GoToLiveCr) })
        },
    ) {
        ZillitText(
            str(S.desktop_cr_over_budget_explainer),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                ZillitText(
                    str(S.desktop_cr_none_over_budget),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
        rows.forEach { overage ->
            val header = overage.header
            val figures = overage.figures
            val over = CrPalette.over
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(5.dp))
                    .background(colors.surfaceSunken)
                    .border(1.dp, colors.border, RoundedCornerShape(5.dp))
                    .drawBehind { drawRect(over, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
                    .padding(start = 17.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText(
                        header.code,
                        style = ZillitTheme.typography.numeric.copy(fontSize = 11.sp),
                        color = CrPalette.over,
                        modifier = Modifier.background(CrPalette.LOCK_RED.copy(alpha = 0.10f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    ZillitText(
                        header.name,
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        modifier = Modifier.weight(1f),
                    )
                    ZillitText(
                        str(
                            S.desktop_cr_over_amount,
                            "$symbol${Money.group(kotlin.math.abs(figures.tv), 0)}",
                        ),
                        style = ZillitTheme.typography.numeric.copy(fontSize = 12.sp),
                        color = CrPalette.over,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ZillitText(
                        str(S.desktop_cr_budget_amount, "$symbol${Money.group(figures.bud, 0)}"),
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    ZillitText(
                        str(S.desktop_cr_efc_amount, "$symbol${Money.group(figures.efc, 0)}"),
                        style = ZillitTheme.typography.bodySmall,
                        color = CrPalette.over,
                    )
                }
                ZillitTextField(
                    value = state.flagNotes[header.code].orEmpty(),
                    onValueChange = { onEvent(WorksheetEvent.SetFlagNote(header.code, it)) },
                    placeholder = str(S.desktop_cr_note_for_producer),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// -- history -----------------------------------------------------------------------------------------

/**
 * "Cost Report History" — the web's History panel, a sheet from the right.
 * The web's list is a placeholder that is always empty; this one lists what
 * the history actually is, the posted reports, and opens each.
 */
@Composable
private fun BoxScope.HistoryPanel(
    state: WorksheetUiState,
    visible: Boolean,
    onEvent: (WorksheetEvent) -> Unit,
    resolveUser: (String) -> String?,
    close: () -> Unit,
) {
    val colors = ZillitTheme.colors
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim.copy(alpha = 0.25f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = close,
                ),
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it },
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
    ) {
        Column(
            Modifier
                .width(380.dp)
                .fillMaxHeight()
                .background(colors.surface)
                .border(1.dp, colors.border)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        str(S.desktop_cr_history_caps),
                        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.textMuted,
                    )
                    ZillitText(state.projectName, style = ZillitTheme.typography.titleSmall)
                }
                ZillitIcon(ZillitIcons.Close, tint = colors.textMuted, size = 14.dp, contentDescription = str(S.close),
                    modifier = Modifier.clip(CircleShape).clickable(onClick = close).padding(6.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
            val rows = state.snapshots.rows
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    ZillitText(
                        str(S.desktop_dm_no_history_recorded),
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(rows, key = { it.id }) { header ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onEvent(WorksheetEvent.OpenSnapshot(header)) }
                                .padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            ZillitText(
                                header.title,
                                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                            ZillitText(
                                listOfNotNull(
                                    header.postedBy?.let { resolveUser(it) ?: it },
                                    CrDates.dateTime(header.postedAtMs).ifBlank { null },
                                ).joinToString(" · "),
                                style = ZillitTheme.typography.labelSmall,
                                color = colors.textMuted,
                            )
                            if (header.postNote.isNotBlank()) {
                                ZillitText(
                                    header.postNote,
                                    style = ZillitTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                    maxLines = 3,
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                    }
                }
            }
        }
    }
}

// -- pieces -------------------------------------------------------------------------------------------

@Composable
private fun SectionCaption(text: String) {
    ZillitText(
        text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.3.sp,
        ),
        color = ZillitTheme.colors.textMuted,
    )
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionCaption(label)
        content()
    }
}

/** The key/value box the web's Lock and Export dialogs summarise with. */
@Composable
private fun SummaryTable(rows: List<Pair<String, String>>, trailing: (@Composable () -> Unit)? = null) {
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(13.dp)),
    ) {
        rows.forEachIndexed { index, (key, value) ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(key, style = ZillitTheme.typography.label.copy(
                    fontWeight = FontWeight.SemiBold,
                ), color = colors.textMuted,
                    modifier = Modifier.width(132.dp))
                ZillitText(
                    value,
                    style = ZillitTheme.typography.numeric.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
        trailing?.let {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            it()
        }
    }
}

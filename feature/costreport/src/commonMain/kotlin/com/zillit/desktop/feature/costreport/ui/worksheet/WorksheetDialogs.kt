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
        title = "Lock Cost Report",
        subtitle = "Lock through ${week?.label.orEmpty()} — this cannot be undone.",
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
                    "Locked periods cannot be reversed",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            ZillitButton(text = "Cancel", onClick = close, variant = ButtonVariant.Secondary)
            ZillitButton(
                text = "Lock",
                onClick = { onEvent(WorksheetEvent.ConfirmLock) },
                variant = ButtonVariant.Danger,
                leadingIcon = ZillitIcons.Shield,
            )
        },
    ) {
        ZillitText(
            "This locks the cost report through the end of the selected week. No new ETC versions can be saved, " +
                "and no source documents dated in those weeks can be created or edited.",
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textMuted,
        )
        SummaryTable(
            listOf(
                "Period" to week?.label.orEmpty(),
                "Range" to week?.range.orEmpty(),
                "Budget Version" to (state.reference.budget(state.ws.applied.budgetKey)?.display ?: "—"),
            ),
        )
        ZillitTextField(
            value = modal?.note.orEmpty(),
            onValueChange = { onEvent(WorksheetEvent.SetLockNote(it)) },
            label = "Note (optional)",
            placeholder = "Why is this period being locked?",
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitNotice(
            text = "Once locked, this period cannot be unlocked. The lock can only move forward to a later week.",
            tone = StatusTone.Pending,
            icon = ZillitIcons.Warning,
        )
    }
}

// -- export ----------------------------------------------------------------------------------

private data class FormatCard(
    val format: ExportFormat,
    val abbreviation: String,
    val name: String,
    val description: String,
    val colors: List<Color>,
)

private val FORMAT_CARDS = listOf(
    FormatCard(
        format = ExportFormat.Pdf,
        abbreviation = "PDF",
        name = "Full Cost Report",
        description = "Formatted weekly CR with section breakdown and sign-off block",
        colors = listOf(Color(0xFFFF7A59), Color(0xFFE23B3B)),
    ),
    FormatCard(
        format = ExportFormat.Xlsx,
        abbreviation = "XLSX",
        name = "Working Report",
        description = "Editable .xlsx with all nominal detail and live formulas",
        colors = listOf(Color(0xFF34C97A), Color(0xFF138A52)),
    ),
    FormatCard(
        format = ExportFormat.Csv,
        abbreviation = "CSV",
        name = "Raw Export",
        description = "Plain comma-separated rows — no formatting, for re-import",
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
        title = "Export Cost Report",
        subtitle = "Export ${week?.label.orEmpty()} in your chosen format.",
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
                    "Download starts immediately",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            ZillitButton(text = "Cancel", onClick = close, variant = ButtonVariant.Secondary)
            ZillitButton(
                text = "Export & Download",
                onClick = { onEvent(WorksheetEvent.ConfirmExport) },
                leadingIcon = ZillitIcons.Download,
            )
        },
    ) {
        SectionCaption("Choose a format")
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
                "Budget Version" to (state.reference.budget(state.ws.applied.budgetKey)?.display ?: "—"),
                "Period" to week?.label.orEmpty(),
                "Generated by" to (state.generatedBy ?: "—"),
            ),
            trailing = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText("Status", style = ZillitTheme.typography.label.copy(
                        fontWeight = FontWeight.SemiBold,
                    ), color = colors.textMuted,
                        modifier = Modifier.weight(1f))
                    if (state.isLocked) {
                        ZillitStatusPill(label = "Locked", tone = StatusTone.Neutral)
                    } else {
                        ZillitStatusPill(label = "Open", tone = StatusTone.Done, dot = true)
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
        title = "Save Version",
        onDismiss = close,
        visible = modal != null,
        width = 380.dp,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = close,
                variant = ButtonVariant.Secondary,
                enabled = !state.savingVersion,
            )
            ZillitButton(
                text = if (state.savingVersion) "Saving…" else "Save Version",
                onClick = { onEvent(WorksheetEvent.ConfirmSaveVersion) },
                loading = state.savingVersion,
            )
        },
    ) {
        ZillitText(
            "Snapshot the current cost report state.",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        ZillitTextField(
            value = modal?.label.orEmpty(),
            onValueChange = { onEvent(WorksheetEvent.SetVersionLabel(it)) },
            label = "Label",
            placeholder = "e.g. End of week 4, Pre-producer review…",
            enabled = !state.savingVersion,
            onImeAction = { onEvent(WorksheetEvent.ConfirmSaveVersion) },
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// -- publish ------------------------------------------------------------------------------------

private val ALL_COMPANIES = CrCompany(id = "", name = "All Companies — Consolidated")
private val LIVE_BUDGET = BudgetVersion(id = "", version = "", label = "Live (current)", status = "")
private val NO_VERSION = EtcVersion(id = "", label = "None — no ETC overrides")

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
        title = "Publish Cost Report",
        onDismiss = close,
        visible = modal != null,
        width = 480.dp,
        actions = {
            ZillitButton(text = "Cancel", onClick = close, variant = ButtonVariant.Secondary)
            ZillitButton(
                text = if (state.posting != null) "Publishing…" else "Publish CR",
                onClick = { onEvent(WorksheetEvent.ConfirmPublish) },
                loading = state.posting != null,
            )
        },
    ) {
        ZillitText(
            "Publish a snapshot of the current cost report.",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        Field("Cadence") {
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
        Field("Company") {
            ZillitSelect(
                value = reference.companies.firstOrNull { it.id == form.companyId } ?: ALL_COMPANIES,
                options = listOf(ALL_COMPANIES) + reference.companies,
                onSelect = { edit(form.copy(companyId = it.id.ifBlank { null })) },
                label = { it.name.ifBlank { it.id } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Field("Budget Version") {
            ZillitSelect(
                value = reference.budget(form.budgetKey) ?: LIVE_BUDGET,
                options = listOf(LIVE_BUDGET) + reference.budgets,
                onSelect = { edit(form.copy(budgetKey = it.version.ifBlank { null })) },
                label = { it.display },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Field("Currency") {
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
        Field("CR Version") {
            ZillitSelect(
                value = form.versions.firstOrNull { it.id == form.etcVersionId } ?: NO_VERSION,
                options = listOf(NO_VERSION) + form.versions,
                onSelect = { edit(form.copy(etcVersionId = it.id.ifBlank { null })) },
                label = { if (it.id.isBlank()) it.label else it.postOptionLabel },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (form.cadence == PostCadence.Custom) {
            Field("Period (custom)") {
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
                "End can't be later than today — defaults to today.",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
        Field("Post Note (optional)") {
            ZillitTextField(
                value = form.note,
                onValueChange = { edit(form.copy(note = it)) },
                placeholder = "Add accountant note to publish with this CR post…",
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
        title = "Over-Budget Lines — ${state.ws.week?.let { "Week ${it.number}" }.orEmpty()}",
        icon = ZillitIcons.Warning,
        onDismiss = close,
        visible = visible,
        width = 560.dp,
        actions = {
            ZillitButton(text = "Close", onClick = close, variant = ButtonVariant.Secondary)
            ZillitButton(text = "Go to Live CR", onClick = { onEvent(WorksheetEvent.GoToLiveCr) })
        },
    ) {
        ZillitText(
            "These lines are tracking over their budget allocation.",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                ZillitText(
                    "No lines currently over budget",
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
                        "($symbol${Money.group(kotlin.math.abs(figures.tv), 0)}) over",
                        style = ZillitTheme.typography.numeric.copy(fontSize = 12.sp),
                        color = CrPalette.over,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ZillitText(
                        "Budget: $symbol${Money.group(figures.bud, 0)}",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    ZillitText(
                        "EFC: $symbol${Money.group(figures.efc, 0)}",
                        style = ZillitTheme.typography.bodySmall,
                        color = CrPalette.over,
                    )
                }
                ZillitTextField(
                    value = state.flagNotes[header.code].orEmpty(),
                    onValueChange = { onEvent(WorksheetEvent.SetFlagNote(header.code, it)) },
                    placeholder = "Note for producer…",
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
                        "COST REPORT HISTORY",
                        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.textMuted,
                    )
                    ZillitText(state.projectName, style = ZillitTheme.typography.titleSmall)
                }
                ZillitIcon(ZillitIcons.Close, tint = colors.textMuted, size = 14.dp, contentDescription = "Close",
                    modifier = Modifier.clip(CircleShape).clickable(onClick = close).padding(6.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
            val rows = state.snapshots.rows
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    ZillitText(
                        "No history recorded.",
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

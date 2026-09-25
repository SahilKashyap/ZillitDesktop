package com.zillit.desktop.feature.cashexpenses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.FloatDetails
import com.zillit.desktop.feature.cashexpenses.domain.FloatReturn
import com.zillit.desktop.feature.cashexpenses.ui.pages.PcCard
import com.zillit.desktop.feature.cashexpenses.ui.pages.PcField
import com.zillit.desktop.feature.cashexpenses.ui.pages.receiptsLabel

/**
 * Float Details — the web's `PreviousFloatDetailModal`, opened by
 * [CashEvent.OpenFloatDetail] from Active Floats, the crew's float pages and
 * the overviews. Drawn at the screen root.
 *
 * Everything comes from `GET /float-requests/{id}/details`: the float, its
 * totals, the batches posted against it, its top-ups and its cash returns.
 * The BS code can be corrected here by an accountant while nothing has been
 * spent against the float ([CashFloat.bsCodeEditable]); everyone else reads it.
 */
@Composable
fun FloatDetailDialog(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val open = state.floatDetail
    val float = open?.details?.float
    ZillitDialogShell(
        title = float?.let { str(S.desktop_pc_float_ref_title, it.requestNumber.ifBlank { "—" }) }
            ?: str(S.ah_float_details),
        visible = open != null,
        onDismiss = { onEvent(CashEvent.CloseFloatDetail) },
        icon = ZillitIcons.Wallet,
        width = DIALOG_WIDTH,
        actions = {
            if (open != null) {
                ZillitButton(
                    text = str(S.history),
                    onClick = { onEvent(CashEvent.ShowFloatHistory(open.floatId, float?.requestNumber)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Clock,
                )
                Spacer(Modifier.weight(1f))
            }
        },
    ) {
        when {
            open == null -> Unit
            open.loading -> Box(Modifier.fillMaxWidth().padding(PADDING), contentAlignment = Alignment.Center) {
                ZillitSpinner(size = SPINNER)
            }

            open.error != null -> ZillitNotice(
                text = open.error.localised(),
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
            )

            open.details != null && float != null -> DetailBody(state, open.details, float, onEvent)
        }
    }
}

@Composable
private fun ColumnScope.DetailBody(
    state: CashUiState,
    details: FloatDetails,
    float: CashFloat,
    onEvent: (CashEvent) -> Unit,
) {
    HeaderCard(state, details, float, onEvent)
    Section(str(S.desktop_pc_summary)) {
        val money = { amount: Double -> state.formatMoney(amount, float.currency) }
        val totals = details.totals
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Stat(str(S.desktop_issued), money(totals.issued), null)
            Stat(str(S.ah_spent_label), money(totals.spent), ZillitTheme.colors.info)
            Stat(str(S.desktop_pc_topped_up), money(totals.toppedUp), ZillitTheme.colors.accentText)
            Stat(str(S.desktop_pc_final_balance), money(totals.finalBalance), ZillitTheme.colors.success)
            Stat(str(S.desktop_pc_returned), money(totals.returned), ZillitTheme.colors.textMuted)
        }
    }
    CustomFields(state, float)
    Section(str(S.desktop_pc_posted_batches_count, details.batches.size)) {
        if (details.batches.isEmpty()) {
            EmptyLine(str(S.desktop_pc_no_batches_posted))
        } else {
            PcCard(modifier = Modifier.fillMaxWidth()) {
                details.batches.forEachIndexed { index, batch ->
                    if (index > 0) ZillitDivider()
                    PostedBatch(state, batch, onEvent)
                }
            }
        }
    }
    Section(str(S.desktop_pc_topups_count, details.topUps.size)) {
        if (details.topUps.isEmpty()) {
            EmptyLine(str(S.desktop_pc_no_topups_requested))
        } else {
            PcCard(modifier = Modifier.fillMaxWidth()) {
                details.topUps.forEachIndexed { index, topUp ->
                    if (index > 0) ZillitDivider()
                    TopUpLine(state, topUp)
                }
            }
        }
    }
    Section(str(S.desktop_pc_cash_returns_count, details.returns.size)) {
        if (details.returns.isEmpty()) {
            EmptyLine(str(S.desktop_pc_no_returns_recorded))
        } else {
            PcCard(modifier = Modifier.fillMaxWidth()) {
                details.returns.forEachIndexed { index, cashReturn ->
                    if (index > 0) ZillitDivider()
                    ReturnLine(state, cashReturn)
                }
            }
        }
    }
}

/** Reference, status, the dates, the purpose, what was asked for, and the company and BS code. */
@Suppress("LongMethod") // The card and its BS-code editor.
@Composable
private fun HeaderCard(state: CashUiState, details: FloatDetails, float: CashFloat, onEvent: (CashEvent) -> Unit) {
    var draft by remember(float.id) { mutableStateOf<String?>(null) }
    val saving = state.floatDetail?.savingBsCode == true
    PcCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    ) {
                        ZillitText(
                            text = "#${float.requestNumber.ifBlank { "—" }}",
                            style = ZillitTheme.typography.titleSmall,
                        )
                        FloatStatusPill(float.status)
                    }
                    ZillitText(
                        text = listOfNotNull(
                            str(S.desktop_ce_submitted_on, EpochDate.date(float.createdAt).ifBlank { "—" }),
                            float.activatedAt?.let { str(S.desktop_pc_collected_date, EpochDate.date(it)) },
                            float.closedAt?.let { str(S.desktop_pc_closed_date, EpochDate.date(it)) },
                        ).joinToString(" · "),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    float.purpose?.takeIf { it.isNotBlank() }?.let {
                        ZillitText(
                            text = "“$it”",
                            style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                            color = ZillitTheme.colors.textSecondary,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Label(str(S.av_chip_requested))
                    ZillitText(
                        text = state.formatMoney(details.totals.requested, float.currency),
                        style = ZillitTheme.typography.titleMedium,
                    )
                }
            }
            ZillitDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) {
                val company = float.companyName?.takeIf { it.isNotBlank() }
                    ?: state.companies.firstOrNull { it.id == float.companyId }?.name
                PcField(str(S.company), company ?: "—", Modifier.weight(1f))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    Label(str(S.desktop_pc_bs_code_label))
                    val editing = draft
                    if (editing == null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        ) {
                            ZillitText(text = float.bsCode?.takeIf { it.isNotBlank() } ?: "—")
                            if (float.bsCodeEditable(state.viewer.isAccountant)) {
                                ZillitButton(
                                    text = str(S.edit),
                                    onClick = { draft = float.bsCode.orEmpty() },
                                    variant = ButtonVariant.Secondary,
                                    size = ButtonSize.Small,
                                )
                            }
                        }
                    } else {
                        BsCodePicker(
                            value = editing,
                            onValueChange = { draft = it },
                            chart = state.chartAccounts,
                            onEvent = onEvent,
                            placeholder = str(S.desktop_pc_bs_code_placeholder),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                            ZillitButton(
                                text = str(S.save),
                                onClick = {
                                    onEvent(CashEvent.SaveFloatBsCode(float.id, editing))
                                    draft = null
                                },
                                size = ButtonSize.Small,
                                enabled = editing.isNotBlank() && !saving,
                                loading = saving,
                            )
                            ZillitButton(
                                text = str(S.cancel),
                                onClick = { draft = null },
                                variant = ButtonVariant.Tertiary,
                                size = ButtonSize.Small,
                                enabled = !saving,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The float request form's extra answers, ids shown as names (`resolveCustomFieldValue`). */
@Composable
private fun CustomFields(state: CashUiState, float: CashFloat) {
    val groups = float.customFields.filter { it.fields.isNotEmpty() }
    if (groups.isEmpty()) return
    val people = LocalCashPeople.current
    Section(str(S.ah_section_additional_details)) {
        PcCard(modifier = Modifier.fillMaxWidth()) {
            groups.forEachIndexed { index, group ->
                if (index > 0) ZillitDivider()
                Column(
                    modifier = Modifier.padding(ZillitTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    if (groups.size > 1) Label(group.section.ifBlank { str(S.details) })
                    group.fields.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                            pair.forEach { field ->
                                val shown = when {
                                    field.value.isBlank() -> "—"
                                    field.selectionType == "user" -> people.nameOf(field.value)
                                    field.selectionType == "department" ->
                                        state.departmentName(field.value) ?: field.value
                                    else -> field.value
                                }
                                PcField(field.name.ifBlank { field.label }, shown, Modifier.weight(1f))
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** One posted batch, opened onto its receipts (`PreviousFloatDetailModal.jsx:408-503`). */
@Suppress("LongMethod") // The row, the meta strip and the receipts table.
@Composable
private fun PostedBatch(state: CashUiState, batch: ClaimBatch, onEvent: (CashEvent) -> Unit) {
    val expanded = batch.id in state.floatDetailClaims
    val claims = state.floatDetailClaims[batch.id]
    val settlement = settlementLabel(batch.settlementType)
    val followUp = followUpLabel(batch.followUp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEvent(CashEvent.ToggleDetailBatch(batch.id)) }
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(
            icon = if (expanded) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
            tint = ZillitTheme.colors.textMuted,
            size = CHEVRON,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(text = batch.reference, style = ZillitTheme.typography.label)
                when (batch.followUp) {
                    TOP_UP -> ZillitStatusPill(label = str(S.desktop_pc_top_up_chip), tone = StatusTone.Pending)
                    CLOSE -> ZillitStatusPill(label = str(S.close), tone = StatusTone.Rejected)
                }
            }
            ZillitText(
                text = listOf(
                    str(S.desktop_pc_posted_date, EpochDate.dateTime(batch.postedAt).ifBlank { "—" }),
                    receiptsLabel(batch.claimCount),
                    settlement,
                ).joinToString(" · "),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitText(
            text = state.formatMoney(batch.totalGross, batch.currency),
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.info,
        )
    }
    if (!expanded) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = "${str(S.desktop_ce_settlement).uppercase()} $settlement    " +
                "${str(S.desktop_pc_follow_up).uppercase()} $followUp",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textSecondary,
        )
        when {
            claims == null -> ZillitSpinner(size = SPINNER)
            claims.isEmpty() -> EmptyLine(str(S.desktop_pc_no_claims_in_batch))
            else -> {
                ClaimCells(
                    listOf(
                        "#",
                        str(S.description),
                        str(S.amount),
                        str(S.desktop_ce_settlement),
                        str(S.desktop_pc_follow_up),
                        str(S.date),
                    ),
                    header = true,
                )
                claims.forEachIndexed { index, claim ->
                    ClaimCells(
                        listOf(
                            (index + 1).toString(),
                            claim.description.ifBlank { "—" },
                            state.formatMoney(claim.grossAmount, batch.currency),
                            settlement,
                            followUp,
                            EpochDate.date(claim.receiptDate).ifBlank { "—" },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClaimCells(cells: List<String>, header: Boolean = false) {
    val style = if (header) ZillitTheme.typography.labelSmall else ZillitTheme.typography.bodySmall
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        cells.forEachIndexed { index, cell ->
            ZillitText(
                text = if (header) cell.uppercase() else cell,
                style = style,
                color = if (header) ZillitTheme.colors.textMuted else null,
                maxLines = 1,
                textAlign = if (index == 2) TextAlign.End else null,
                modifier = Modifier.weight(CELL_WEIGHTS[index]),
            )
        }
    }
}

@Composable
private fun TopUpLine(state: CashUiState, topUp: CashTopUp) {
    val (label, tone) = when (topUp.status) {
        "completed" -> str(S.completed) to StatusTone.Done
        "partial" -> str(S.ah_status_partial) to StatusTone.Progress
        "skipped" -> str(S.ah_skipped_toast) to StatusTone.Neutral
        else -> str(S.pending) to StatusTone.Pending
    }
    val acted = topUp.status != PENDING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = str(S.desktop_pc_amount_requested, state.formatMoney(topUp.amount, topUp.currency)),
                    style = ZillitTheme.typography.label,
                )
                ZillitStatusPill(label = label, tone = tone)
            }
            ZillitText(
                text = listOfNotNull(
                    str(S.docusign_created_on, EpochDate.dateTime(topUp.createdAt).ifBlank { "—" }),
                    topUp.updatedAt?.takeIf { acted }?.let { str(S.desktop_pc_actioned_date, EpochDate.dateTime(it)) },
                    topUp.note?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        topUp.issuedAmount?.takeIf { acted && topUp.status != SKIPPED }?.let {
            ZillitText(
                text = str(S.desktop_pc_amount_issued, state.formatMoney(it, topUp.currency)),
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                color = ZillitTheme.colors.accentText,
            )
        }
    }
}

@Composable
private fun ReturnLine(state: CashUiState, cashReturn: FloatReturn) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = str(S.desktop_pc_amount_returned, state.formatMoney(cashReturn.amount, cashReturn.currency)),
                style = ZillitTheme.typography.label,
            )
            ZillitStatusPill(label = returnReasonLabel(cashReturn.reason), tone = StatusTone.Rejected)
        }
        ZillitText(
            text = listOfNotNull(
                str(S.desktop_pc_recorded_date, EpochDate.dateTime(cashReturn.recordedAt).ifBlank { "—" }),
                cashReturn.receivedDate?.let { str(S.desktop_pc_received_date, EpochDate.date(it)) },
                cashReturn.notes?.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/**
 * A float's audit trail — `GET /float-requests/{id}/history` — over whatever
 * opened it (`PCFloatsPage.jsx:959-966`). Drawn at the screen root, after the
 * details dialog, so it opens on top of it.
 */
@Composable
fun FloatHistoryDialog(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val open = state.floatHistory
    val people = LocalCashPeople.current
    ZillitDialogShell(
        title = str(S.history),
        subtitle = open?.reference?.takeIf { it.isNotBlank() }?.let { str(S.desktop_pc_float_ref_title, it) },
        visible = open != null,
        onDismiss = { onEvent(CashEvent.ShowFloatHistory(null)) },
        icon = ZillitIcons.Clock,
    ) {
        val entries = open?.entries
        when {
            open == null -> Unit
            entries == null -> ZillitSpinner(size = SPINNER)
            entries.isEmpty() -> EmptyLine(str(S.desktop_ce_no_history_yet))
            else -> entries.forEach { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                    ZillitText(
                        text = entry.action.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                        style = ZillitTheme.typography.bodyMedium,
                    )
                    ZillitText(
                        text = listOfNotNull(
                            entry.userId?.let { people.nameOf(it) },
                            EpochDate.dateTime(entry.at).takeIf { it.isNotBlank() },
                            entry.note?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
        }
    }
}

// -- pieces -------------------------------------------------------------------------------

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitText(
            text = title.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.textSecondary,
        )
        content()
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String, tint: Color?) {
    PcField(
        label = label,
        value = value,
        valueColor = tint,
        modifier = Modifier
            .weight(1f)
            .background(ZillitTheme.colors.surfaceSunken, ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
    )
}

@Composable
private fun Label(text: String) {
    ZillitText(text = text.uppercase(), style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
}

@Composable
private fun EmptyLine(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.lg),
    )
}

/** The web's `SETTLEMENT_LABEL`. */
private fun settlementLabel(type: String?): String = when (type) {
    null, "" -> "—"
    "REIMBURSE" -> str(S.desktop_ce_reimburse)
    "REDUCE_FLOAT", "REDUCE" -> str(S.desktop_ce_reduce_float)
    else -> type
}

/** The web's `followUpLabel`. */
private fun followUpLabel(key: String?): String = when (key) {
    TOP_UP -> str(S.desktop_pc_reimburse_to_float)
    CLOSE -> str(S.desktop_pc_close_the_float_title)
    else -> "—"
}

/** The returns list's reason chip — shorter than the form's option labels. */
private fun returnReasonLabel(reason: String?): String = when (reason) {
    ReturnReasons.CLOSE_FULL -> str(S.desktop_pc_reason_full_return)
    ReturnReasons.CONTINUE_PARTIAL -> str(S.desktop_pc_reason_partial_return)
    ReturnReasons.CANCEL -> str(S.desktop_ce_return_cancelled)
    ReturnReasons.OVERSPEND -> str(S.desktop_pc_reason_overspend)
    "close_other" -> str(S.desktop_pc_reason_other_closed)
    "continue_other" -> str(S.desktop_pc_reason_other_continued)
    null, "" -> "—"
    else -> reason
}

private const val TOP_UP = "top_up"
private const val CLOSE = "close"
private const val PENDING = "pending"
private const val SKIPPED = "skipped"
private val CELL_WEIGHTS = listOf(0.3f, 2f, 1f, 1f, 1f, 1f)
private val DIALOG_WIDTH = 820.dp
private val PADDING = 32.dp
private val SPINNER = 16.dp
private val CHEVRON = 12.dp

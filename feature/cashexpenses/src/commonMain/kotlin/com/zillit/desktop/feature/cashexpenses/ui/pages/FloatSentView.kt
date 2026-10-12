package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashFormFields
import com.zillit.desktop.feature.cashexpenses.domain.CrewDates
import com.zillit.desktop.feature.cashexpenses.domain.FloatFlow
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.FloatStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.SentFloatRequest
import com.zillit.desktop.feature.cashexpenses.ui.date
import com.zillit.desktop.feature.cashexpenses.ui.floatRequestFields
import com.zillit.desktop.feature.cashexpenses.ui.floatValue

/**
 * What a crew member sees after sending a float request: the journey with the
 * request at its first stop, and the request read back to them
 * (`PCFloatRequestPage.jsx:615-619, 1441-1544`).
 *
 * The new float is the one that was not in the list before the send; until
 * the reload brings it, the request reads as awaiting approval.
 */
@Composable
internal fun FloatSentView(state: CashUiState, sent: SentFloatRequest, modifier: Modifier = Modifier) {
    val float = state.myFloats.firstOrNull { it.id !in sent.knownFloatIds }
    val status = float?.status ?: FloatStatus.AwaitingApproval
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        FlowChain(status)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surface)
                .border(CREW_HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                .padding(ZillitTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    text = str(S.desktop_pc_float_details),
                    style = ZillitTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                FloatStatusPill(status)
            }
            float?.let { RefAndDate(it.requestNumber, it.createdAt) }
            SentFields(state.copy(floatDraft = sent.draft), sent.currency)
            StageMessage(status)
        }
    }
}

/** The six-step journey: done steps green, the current one accented, a rejection red. */
@Composable
private fun FlowChain(status: FloatStatus) {
    val colors = ZillitTheme.colors
    val labels = listOf(
        str(S.you) to str(S.desktop_ce_cards_submit_request),
        str(S.ah_step_approval) to str(S.desktop_pc_flow_review_approve),
        str(S.desktop_ce_ready_to_collect) to str(S.desktop_pc_flow_cash_prepared),
        str(S.desktop_pc_flow_cash_collected) to str(S.desktop_pc_flow_in_person),
        str(S.desktop_pc_flow_you_spend) to str(S.desktop_pc_flow_keep_receipts),
        str(S.desktop_ce_submit_receipts) to str(S.desktop_pc_flow_via_portal),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(CREW_HAIRLINE, colors.border, ZillitTheme.shapes.large),
    ) {
        FloatFlow.steps(status).forEachIndexed { index, step ->
            val (label, sub) = labels[index]
            val ink = when {
                step.rejected -> colors.danger
                step.done -> colors.success
                step.active -> colors.accentText
                else -> colors.textSecondary
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        when {
                            step.rejected -> colors.dangerSoft
                            step.active -> colors.accentSoft
                            else -> colors.surface
                        },
                    )
                    .padding(ZillitTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    ZillitText(text = label, style = ZillitTheme.typography.label, color = ink)
                    ZillitText(
                        text = if (step.rejected) str(S.rejected) else sub,
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }
        }
    }
}

/** "Ref. #12" and "Submitted On 03 Sep, 2026", once the new float has loaded. */
@Composable
private fun RefAndDate(requestNumber: String, createdAt: Long?) {
    val reference = requestNumber.takeIf { it.isNotBlank() }
    val submitted = date(createdAt).takeIf { it != "—" }
    if (reference == null && submitted == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) {
        reference?.let { SentField(str(S.desktop_pc_ref), "#$it", Modifier.weight(1f)) }
        submitted?.let { SentField(str(S.desktop_pc_submitted_on_label), it, Modifier.weight(1f)) }
    }
}

/** The template's fields as sent, three to a row, long answers on their own below. */
@Composable
private fun SentFields(sent: CashUiState, currency: String) {
    val fields = sent.floatRequestFields.filterNot {
        // Days is folded into How long, and the removed pair is not collected.
        it.label == CashFormFields.DURATION || it.label in CashFormFields.REMOVED
    }
    val (long, short) = fields.partition { it.type == CashFormFields.TEXTAREA }
    short.chunked(COLUMNS).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) {
            row.forEach { field ->
                SentField(fieldName(field), displayValue(sent, field, currency), Modifier.weight(1f))
            }
            repeat(COLUMNS - row.size) { Row(Modifier.weight(1f)) {} }
        }
    }
    long.forEach { field ->
        val value = displayValue(sent, field, currency)
        if (value != EM_DASH) SentField(fieldName(field), value, Modifier.fillMaxWidth())
    }
}

private fun fieldName(field: FormField): String = when (field.label) {
    CashFormFields.DURATION_TYPE -> str(S.desktop_pc_how_long)
    CashFormFields.COLLECT_DATE -> str(S.desktop_pc_collect_date_label)
    else -> field.name.ifBlank { field.label }
}

@Composable
private fun displayValue(sent: CashUiState, field: FormField, currency: String): String {
    val value = sent.floatValue(field).trim()
    return when (field.label) {
        CashFormFields.USER ->
            LocalCashPeople.current.nameOrNull(value.ifBlank { sent.viewer.userId }) ?: str(S.user_label)
        CashFormFields.DEPARTMENT -> sent.departmentName(value) ?: EM_DASH
        CashFormFields.AMOUNT -> sent.formatMoney(value.toDoubleOrNull(), currency)
        CashFormFields.DURATION_TYPE -> durationText(sent, value)
        CashFormFields.COLLECT_DATE -> date(CrewDates.utcMillis(value))
        CashFormFields.EPISODE, CashFormFields.COLLECTION_METHOD ->
            CashFormFields.optionLabel(field.label, value).ifBlank { EM_DASH }
        else -> value.ifBlank { EM_DASH }
    }
}

/** "Run of Show", or "5 days" from the Days answer (`getDisplayValue` merges the two). */
private fun durationText(sent: CashUiState, type: String): String {
    if (type == CashFormFields.RUN_OF_SHOW) return str(S.desktop_pc_run_of_show)
    val days = sent.floatDraft.duration.trim().toIntOrNull()?.takeIf { it > 0 } ?: return EM_DASH
    return if (days == 1) str(S.desktop_pc_days_one, days) else str(S.ah_days_format, days)
}

@Composable
private fun SentField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        CrewHeading(label)
        ZillitText(text = value, style = ZillitTheme.typography.bodyMedium)
    }
}

/** The one-line stage under the details (`stageMap`). */
@Composable
private fun StageMessage(status: FloatStatus) {
    val (text, tone) = when (status) {
        FloatStatus.Approved, FloatStatus.AcctOverride -> str(S.desktop_pc_stage_approved) to StatusTone.Progress
        FloatStatus.ReadyToCollect -> str(S.desktop_pc_stage_ready) to StatusTone.Ready
        FloatStatus.Collected, FloatStatus.Active -> str(S.desktop_pc_stage_collected) to StatusTone.Ready
        FloatStatus.Spending -> str(S.desktop_pc_stage_spending) to StatusTone.Pending
        FloatStatus.Spent -> str(S.desktop_pc_stage_spent) to StatusTone.Escalated
        FloatStatus.PendingReturn -> str(S.desktop_pc_stage_pending_return) to StatusTone.Rejected
        FloatStatus.Rejected -> str(S.desktop_pc_stage_rejected) to StatusTone.Rejected
        FloatStatus.Cancelled -> str(S.desktop_ce_return_cancelled) to StatusTone.Rejected
        FloatStatus.Closed -> str(S.desktop_ce_float_closed) to StatusTone.Neutral
        FloatStatus.AwaitingApproval, FloatStatus.Unknown -> str(S.desktop_pc_stage_awaiting) to StatusTone.Pending
    }
    val ink = toneContent(tone)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIcon(icon = ZillitIcons.Check, tint = ink, size = STAGE_ICON)
        ZillitText(text = text, style = ZillitTheme.typography.label, color = ink)
    }
}

private const val COLUMNS = 3
private const val EM_DASH = "—"
private val STAGE_ICON = 16.dp

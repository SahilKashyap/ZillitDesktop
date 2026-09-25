package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUps
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPerson
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.FundsAction
import com.zillit.desktop.feature.cashexpenses.ui.LimitAlert
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople

/**
 * The accountant's top-up inbox — the web's `PCTopUpsPage.jsx`.
 *
 * A card per top-up rather than a table row, because each one is a decision
 * about a float: the card carries the float's balance, what was issued, what
 * has been spent against the limit and the amount asked for, so the accountant
 * can see whether the cash fits before handing it over. Pending top-ups come
 * first, with their actions; settled ones follow, and open their details.
 */
@Composable
fun TopUpsPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    var filter by remember { mutableStateOf(CashTopUps.Filter.All) }
    var sort by remember { mutableStateOf(CashTopUps.Sort.Oldest) }
    var detailId by remember { mutableStateOf<String?>(null) }
    val (pending, settled) = CashTopUps.sections(state.topUps, filter, sort)
    fun act(action: FundsAction) = onEvent(CashEvent.Funds(action))

    Box(Modifier.fillMaxSize()) {
        ScrollingPage {
            TitledNotice(
                title = str(S.ah_pc_topups_title),
                body = str(S.ah_pc_topups_subtitle),
                icon = ZillitIcons.Wallet,
            )
            FilterBar(filter, sort, onFilter = { filter = it }, onSort = { sort = it })

            when {
                state.loading && state.topUps.isEmpty() -> TopUpSkeleton()
                pending.isEmpty() && settled.isEmpty() -> ZillitEmptyState(
                    title = str(S.ah_payment_runs_empty_title),
                    message = if (filter == CashTopUps.Filter.All) {
                        str(S.desktop_pc_no_topups_now)
                    } else {
                        str(S.desktop_pc_no_filter_topups_now, filterLabel(filter).lowercase())
                    },
                    icon = ZillitIcons.Check,
                )

                else -> {
                    if (pending.isNotEmpty()) {
                        PendingHeader(pending.size)
                        pending.forEach { TopUpCard(state, it, pending = true, onEvent = onEvent) }
                    }
                    if (settled.isNotEmpty()) {
                        Caption(str(S.desktop_pc_recently_settled), modifier = Modifier.padding(top = 8.dp))
                        settled.forEach { row ->
                            TopUpCard(
                                state,
                                row,
                                pending = false,
                                onEvent = onEvent,
                                modifier = Modifier.clickable { detailId = row.id },
                            )
                        }
                    }
                }
            }
        }

        PartialTopUpDialog(state, ::act)
        TopUpDetailDialog(state, state.topUps.firstOrNull { it.id == detailId }, onDismiss = { detailId = null })
        LimitAlertDialog(state.fundsUi.limitAlert) { act(FundsAction.DismissLimitAlert) }
    }
}

@Composable
private fun FilterBar(
    filter: CashTopUps.Filter,
    sort: CashTopUps.Sort,
    onFilter: (CashTopUps.Filter) -> Unit,
    onSort: (CashTopUps.Sort) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Caption(str(S.dm_nda_quick_filters))
        CashTopUps.Filter.entries.forEach { option ->
            ZillitChoiceChip(
                label = filterLabel(option),
                selected = option == filter,
                onClick = { onFilter(option) },
            )
        }
        Spacer(Modifier.weight(1f))
        ZillitSelect(
            value = sort,
            options = CashTopUps.Sort.entries,
            onSelect = onSort,
            label = { sortLabel(it) },
            modifier = Modifier.width(SORT_WIDTH),
        )
    }
}

@Composable
private fun PendingHeader(count: Int) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.warningSoft)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(ZillitIcons.Bell, tint = colors.warning)
        ZillitText(
            text = str(S.ah_pending_topups).uppercase(),
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            color = colors.warning,
        )
        ZillitStatusPill(label = count.toString(), tone = StatusTone.Pending)
        Spacer(Modifier.weight(1f))
        ZillitText(
            text = str(S.desktop_pc_action_required),
            style = ZillitTheme.typography.bodySmall,
            color = colors.warning,
        )
    }
}

/** One top-up: who, which float, the float's figures, and — while pending — what to do with it. */
@Suppress("LongMethod") // The header, the four figures, the note and the actions of one card.
@Composable
private fun TopUpCard(
    state: CashUiState,
    topUp: CashTopUp,
    pending: Boolean,
    onEvent: (CashEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stripe = ZillitTheme.colors.accent
    val holderId = topUp.userId.ifBlank { topUp.holderName }
    val designation = state.assignees.firstOrNull { it.userId == holderId }?.designation?.takeIf(String::isNotBlank)
    val code = topUp.currency
    FundsCard(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (pending) 1f else SETTLED_ALPHA)
            .drawBehind { if (pending) drawRect(stripe, size = Size(STRIPE.toPx(), size.height)) },
    ) {
        Column(
            modifier = Modifier.padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    CashPerson(
                        userId = holderId,
                        recordedName = topUp.holderName,
                        secondary = designation,
                        size = AVATAR,
                    )
                    topUp.floatRequestNumber?.takeIf(String::isNotBlank)?.let {
                        ZillitText(
                            text = "— #$it",
                            style = ZillitTheme.typography.numeric,
                            color = ZillitTheme.colors.textMuted,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    TopUpStatusPill(topUp.status)
                    ZillitText(
                        text = EpochDate.dateTime(topUp.createdAt).ifEmpty { "—" },
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                FigureCell(
                    label = str(S.ah_current_balance),
                    value = state.formatMoney(topUp.floatBalance, code),
                    color = ZillitTheme.colors.success,
                    modifier = Modifier.weight(1f),
                )
                Column(modifier = Modifier.weight(1f)) {
                    FigureCell(
                        label = str(S.ah_issued_float),
                        value = state.formatMoney(topUp.floatIssued, code),
                    )
                    ZillitProgressBar(
                        fraction = CashTopUps.spentFraction(topUp),
                        modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 0.dp),
                    )
                    ZillitText(
                        text = str(
                            S.desktop_pc_spent_of,
                            state.formatMoney(CashTopUps.spent(topUp), code),
                            state.formatMoney(topUp.floatIssued, code),
                        ),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
                FigureCell(
                    label = str(S.ah_spent_label),
                    value = state.formatMoney(CashTopUps.spent(topUp), code),
                    color = ZillitTheme.colors.info,
                    sub = str(S.desktop_pc_of_limit, state.formatMoney(topUp.floatRequestedAmount, code)),
                    modifier = Modifier.weight(1f),
                )
                FigureCell(
                    label = str(S.ah_topup_amount_label),
                    value = state.formatMoney(topUp.amount, code),
                    color = ZillitTheme.colors.accent,
                    highlight = true,
                    modifier = Modifier.weight(1f),
                )
            }

            if (pending) {
                topUp.note?.takeIf(String::isNotBlank)?.let { NoteBox(it) }
                ZillitDivider()
                TopUpActions(state, topUp, onEvent)
            }
        }
    }
}

@Composable
private fun TopUpActions(state: CashUiState, topUp: CashTopUp, onEvent: (CashEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitButton(
            text = str(S.desktop_pc_mark_topped_up),
            onClick = { onEvent(CashEvent.Funds(FundsAction.CompleteTopUp(topUp.id))) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Check,
            enabled = !state.busy,
        )
        ZillitButton(
            text = str(S.desktop_pc_partial_topup_action),
            onClick = { onEvent(CashEvent.Funds(FundsAction.OpenPartial(topUp.id))) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Minus,
            enabled = !state.busy,
        )
        ZillitButton(
            text = str(S.skip),
            onClick = { onEvent(CashEvent.Funds(FundsAction.SkipTopUp(topUp.id))) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            enabled = !state.busy,
        )
    }
}

@Composable
private fun FigureCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    sub: String? = null,
    highlight: Boolean = false,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.medium)
            .background(if (highlight) colors.warningSoft else Color.Transparent)
            .padding(if (highlight) ZillitTheme.spacing.sm else 0.dp),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Caption(label, color = if (highlight) colors.warning else null)
        ZillitText(
            text = value,
            style = ZillitTheme.typography.titleMedium,
            color = color ?: colors.textPrimary,
            maxLines = 1,
        )
        sub?.let {
            ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
    }
}

@Composable
private fun NoteBox(note: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.warningSoft)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(ZillitIcons.Edit, tint = ZillitTheme.colors.warning, size = 14.dp)
        ZillitText(
            text = "${str(S.note)} $note",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.warning,
        )
    }
}

/** Pending amber; Topped up green; Partial amber; Skipped neutral (`PCTopUpsPage.jsx:546-547`). */
@Composable
private fun TopUpStatusPill(status: String) {
    val (label, tone) = when (status) {
        CashTopUps.COMPLETED -> str(S.desktop_pc_topped_up_status) to StatusTone.Done
        CashTopUps.PARTIAL -> str(S.ah_status_partial) to StatusTone.Pending
        CashTopUps.SKIPPED -> str(S.ah_status_skipped) to StatusTone.Neutral
        else -> str(S.pending) to StatusTone.Pending
    }
    ZillitStatusPill(label = label, tone = tone, dot = true)
}

@Composable
private fun TopUpSkeleton() {
    repeat(SKELETON_ROWS) {
        FundsCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitSkeletonBar(modifier = Modifier.width(160.dp))
                ZillitSkeletonBar(modifier = Modifier.width(260.dp))
            }
        }
    }
}

/** The web's Partial Top-Up modal: the amount actually handed over, and why (`PCTopUpsPage.jsx:621-706`). */
@Composable
private fun PartialTopUpDialog(state: CashUiState, act: (FundsAction) -> Unit) {
    val draft = state.fundsUi.partial
    val topUp = draft?.let { d -> state.topUps.firstOrNull { it.id == d.topUpId } }
    val people = LocalCashPeople.current
    ZillitDialogShell(
        title = str(S.ah_partial_topup),
        subtitle = topUp?.let {
            str(
                S.desktop_pc_partial_for,
                people.nameOf(it.userId.ifBlank { it.holderName }, it.holderName),
                it.floatRequestNumber.orEmpty(),
            )
        },
        icon = ZillitIcons.Edit,
        visible = draft != null,
        onDismiss = { act(FundsAction.ClosePartial) },
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.cancel),
                onClick = { act(FundsAction.ClosePartial) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (state.busy) str(S.ah_submitting) else str(S.desktop_pc_submit_partial_topup),
                onClick = { act(FundsAction.SubmitPartial) },
                enabled = draft != null && draft.note.isNotBlank() && !state.busy,
            )
        },
    ) {
        if (draft == null) return@ZillitDialogShell
        ZillitTextField(
            value = draft.amount,
            onValueChange = { act(FundsAction.EditPartial(it, draft.note)) },
            label = str(S.desktop_pc_actual_amount_topped_up),
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = draft.note,
            onValueChange = { act(FundsAction.EditPartial(draft.amount, it)) },
            label = "${str(S.note_label)} *",
            placeholder = str(S.ah_partial_note_hint),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A settled top-up's details — the web's Top-Up Details modal (`PCTopUpsPage.jsx:709-781`). */
@Composable
private fun TopUpDetailDialog(state: CashUiState, topUp: CashTopUp?, onDismiss: () -> Unit) {
    val people = LocalCashPeople.current
    ZillitDialogShell(
        title = str(S.ah_topup_details_title),
        icon = ZillitIcons.Wallet,
        visible = topUp != null,
        onDismiss = onDismiss,
    ) {
        if (topUp == null) return@ZillitDialogShell
        val holderId = topUp.userId.ifBlank { topUp.holderName }
        val designation = state.assignees.firstOrNull { it.userId == holderId }?.designation.orEmpty()
        val cells = listOf(
            str(S.ah_holder) to listOf(people.nameOf(holderId, topUp.holderName), designation)
                .filter(String::isNotBlank).joinToString(" — "),
            str(S.ah_float_label) to (topUp.floatRequestNumber?.takeIf(String::isNotBlank)?.let { "#$it" } ?: "—"),
            str(S.amount) to state.formatMoney(topUp.amount, topUp.currency),
            str(S.status) to topUp.status.replaceFirstChar { it.uppercase() }.ifBlank { "—" },
            str(S.ah_float_balance_label) to state.formatMoney(topUp.floatBalance, topUp.currency),
            str(S.ah_float_issued_label) to state.formatMoney(topUp.floatIssued, topUp.currency),
            str(S.ah_created_label) to EpochDate.dateTime(topUp.createdAt).ifEmpty { "—" },
            str(S.ah_updated_label) to EpochDate.dateTime(topUp.updatedAt).ifEmpty { "—" },
        )
        cells.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                pair.forEach { (label, value) -> LabelledValue(label, value, modifier = Modifier.weight(1f)) }
            }
        }
        topUp.note?.takeIf(String::isNotBlank)?.let { LabelledValue(str(S.note_label), "“$it”") }
    }
}

/** The web's warning `ConfirmModal` for a top-up past the float's limit. */
@Composable
internal fun LimitAlertDialog(alert: LimitAlert?, onDismiss: () -> Unit) {
    ZillitDialogShell(
        title = alert?.title.orEmpty(),
        icon = ZillitIcons.Warning,
        visible = alert != null,
        onDismiss = onDismiss,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(text = str(S.close), onClick = onDismiss, variant = ButtonVariant.Tertiary)
            ZillitButton(text = str(S.ok), onClick = onDismiss)
        },
    ) {
        ZillitText(
            text = alert?.message.orEmpty(),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

private fun filterLabel(filter: CashTopUps.Filter): String = when (filter) {
    CashTopUps.Filter.All -> str(S.ah_topup_filter_all)
    CashTopUps.Filter.Pending -> str(S.ah_topup_filter_pending)
    CashTopUps.Filter.Completed -> str(S.ah_topup_filter_completed)
    CashTopUps.Filter.Skipped -> str(S.ah_topup_filter_skipped)
}

private fun sortLabel(sort: CashTopUps.Sort): String = when (sort) {
    CashTopUps.Sort.Oldest -> str(S.ah_oldest_first)
    CashTopUps.Sort.Newest -> str(S.desktop_inv_newest_first)
    CashTopUps.Sort.Largest -> str(S.desktop_pc_largest_first)
}

private const val SETTLED_ALPHA = 0.85f
private const val SKELETON_ROWS = 4
private val STRIPE = 3.dp
private val AVATAR = 38.dp
private val SORT_WIDTH = 160.dp

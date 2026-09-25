package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.TopUpBoard
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.InsightsEvent
import com.zillit.desktop.feature.cardexpenses.ui.components.CardCalcInput
import com.zillit.desktop.feature.cardexpenses.ui.components.InsightBar
import com.zillit.desktop.feature.cardexpenses.ui.components.InsightChips
import com.zillit.desktop.feature.cardexpenses.ui.components.InsightEyebrow
import com.zillit.desktop.feature.cardexpenses.ui.components.InsightFigure
import com.zillit.desktop.feature.cardexpenses.ui.components.InsightTag
import com.zillit.desktop.feature.cardexpenses.ui.components.insightCard
import com.zillit.desktop.feature.cardexpenses.ui.money
import com.zillit.desktop.feature.cardexpenses.ui.unreadRow

/**
 * Top-Up To Do — the web's `TopUpToDoPage.jsx`.
 *
 * A card list, not a table: each pending top-up is its own decision, with
 * the card's balance and limit beside it, and the done ones collect in a
 * table under it. Mark Topped Up and Skip fire on the press, as the web's do;
 * the only questions asked are the partial amount and the over-limit
 * explainer, which says what can still be applied.
 */
@Composable
fun TopUpToDoPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val insights = state.insights
    val filtered = TopUpBoard.filtered(state.topUps, insights.topUpFilter)
    val pending = TopUpBoard.pending(filtered)
    val done = TopUpBoard.done(filtered)
    val showPending = insights.topUpFilter == TopUpBoard.ALL || insights.topUpFilter == TopUpBoard.PENDING

    Box(Modifier.fillMaxSize()) {
        ScrollingPage {
            InsightChips(
                options = TopUpBoard.FILTERS,
                selected = insights.topUpFilter,
                label = ::filterLabel,
                onSelect = { onEvent(InsightsEvent.FilterTopUps(it)) },
            )
            when {
                state.loading && state.topUps.isEmpty() ->
                    Row(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl), Arrangement.Center) { ZillitSpinner() }

                filtered.isEmpty() -> ZillitEmptyState(
                    title = str(S.desktop_ce_insights_topups_empty),
                    icon = ZillitIcons.CreditCard,
                )

                else -> {
                    if (showPending && pending.isNotEmpty()) {
                        PendingBlock(state, pending, state.topUps.count { it.status == TopUpBoard.PENDING }, onEvent)
                    }
                    if (done.isNotEmpty()) DoneBlock(state, done, onEvent)
                }
            }
        }
        TopUpDialogs(state, onEvent)
    }
}

private fun filterLabel(filter: String): String = when (filter) {
    TopUpBoard.ALL -> str(S.all)
    TopUpBoard.PENDING -> str(S.pending)
    "completed" -> str(S.completed)
    "partial" -> str(S.ah_status_partial)
    else -> str(S.ah_skipped_toast)
}

/** The amber "Pending Top-Ups" block (`TopUpToDoPage.jsx:305-467`). */
@Composable
private fun PendingBlock(state: CardUiState, rows: List<CardTopUp>, count: Int, onEvent: (CardEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(modifier = Modifier.fillMaxWidth().insightCard(colors.warningSoft, colors.warning.copy(alpha = 0.3f))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(ZillitIcons.Siren, tint = colors.warning, size = 17.dp)
            InsightEyebrow(str(S.ah_pending_topups), color = colors.warning)
            InsightTag(count.toString(), colors.warningSoft, colors.warning)
            Spacer(Modifier.weight(1f))
            ZillitText(
                text = str(S.desktop_ce_insights_oldest_first),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(2.dp).insightCard(colors.surface, colors.border),
        ) {
            rows.forEachIndexed { index, topUp ->
                if (index > 0) ZillitDivider()
                PendingRow(state, topUp, onEvent)
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // One row of the web's card: identity, figures, note, actions.
@Composable
private fun PendingRow(state: CardUiState, topUp: CardTopUp, onEvent: (CardEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val name = holderName(state, topUp)
    val balance = topUp.cardBalance ?: 0.0
    val limit = topUp.cardLimit ?: 0.0
    val spent = limit - balance
    val insights = state.insights
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (topUp.urgent) ZillitIcon(ZillitIcons.Siren, tint = colors.warning, size = 14.dp)
            ZillitAvatar(name = name, userId = topUp.holderId, size = 36.dp)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText(
                        text = name,
                        style = ZillitTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    // The request's own unread (`TopUpToDoPage.jsx:353-356`).
                    ZillitBadge(count = state.unreadRow("topup_todo", topUp.badgeKey))
                    ZillitText(text = "—", color = colors.textMuted)
                    ZillitText(
                        text = "••••${topUp.cardLastFour ?: "0000"}",
                        style = ZillitTheme.typography.numeric,
                        color = colors.textSecondary,
                    )
                }
                val meta = listOfNotNull(
                    topUp.cardLastFour?.let { str(S.desktop_ce_cards_card_history_sub, it) },
                    topUp.bsControlCode?.let { str(S.desktop_ce_insights_bs_code, it) },
                )
                if (meta.isNotEmpty()) {
                    ZillitText(
                        text = meta.joinToString(" · "),
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }
            if (topUp.urgent) {
                InsightTag(str(S.ah_topup_filter_urgent).uppercase(), colors.dangerSoft, colors.danger)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) {
            InsightFigure(
                label = str(S.desktop_ce_insights_current_bal),
                value = money(balance, topUp.currency),
                color = colors.success,
                modifier = Modifier.weight(1f),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                InsightFigure(label = str(S.desktop_card_card_limit), value = money(limit, topUp.currency))
                InsightBar(fraction = if (limit > 0) (spent / limit).toFloat() else 0f, color = colors.warning)
                ZillitText(
                    text = str(S.desktop_ce_insights_spent_x, money(spent, topUp.currency)),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            InsightFigure(
                label = str(S.desktop_card_top_up_method),
                value = if ((topUp.method ?: RESTORE) == RESTORE) {
                    str(S.desktop_card_restore_float)
                } else {
                    str(S.desktop_ce_insights_expense_amount)
                },
                style = ZillitTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            InsightFigure(
                label = str(S.ah_topup_amount_label),
                value = money(topUp.amount, topUp.currency),
                color = colors.warning,
                style = ZillitTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }

        sourceLine(listOf(topUp))?.let { line ->
            ZillitText(text = line, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }

        topUp.note?.let { note ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .insightCard(colors.warningSoft, colors.warning.copy(alpha = 0.3f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZillitIcon(ZillitIcons.Edit, tint = colors.warning, size = 14.dp)
                ZillitText(
                    text = "${str(S.note)} $note",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.warning,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitButton(
                text = str(S.ah_mark_topped_up),
                onClick = { onEvent(InsightsEvent.MarkToppedUp(topUp.id)) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Check,
                loading = insights.completingId == topUp.id,
                enabled = insights.completingId == null,
            )
            ZillitButton(
                text = str(S.ah_partial_topup),
                onClick = { onEvent(InsightsEvent.OpenPartial(topUp.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            val skipping = insights.skippingId == topUp.id
            ZillitButton(
                text = if (skipping) str(S.ah_skipping) else str(S.skip),
                onClick = { onEvent(InsightsEvent.SkipTopUp(topUp.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !skipping,
                loading = skipping,
            )
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.history),
                onClick = { onEvent(InsightsEvent.OpenHistory(topUp.id, name)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Clock,
            )
        }
    }
}

/**
 * "From: Shell £42.10 + 1 other expense" — the web's `buildSourceLine`
 * (`TopUpToDoPage.jsx:87-98`), which each row calls with itself alone: each
 * API row is its own top-up and is never summed with another.
 */
private fun sourceLine(topUps: List<CardTopUp>): String? {
    val named = topUps.filter { it.receiptMerchant != null }
    val rest = topUps.size - named.size
    val parts = named.take(2).map { row ->
        val amount = row.receiptAmount?.takeIf { it != 0.0 }?.let { " ${money(it, row.currency)}" }.orEmpty()
        "${row.receiptMerchant}$amount"
    }.toMutableList()
    if (named.size > 2) parts += str(S.desktop_ce_insights_n_more, named.size - 2)
    if (rest > 0) {
        parts += if (rest > 1) {
            str(S.desktop_ce_insights_n_other_expenses, rest)
        } else {
            str(S.desktop_ce_insights_one_other_expense, rest)
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.let { str(S.desktop_ce_insights_from_x, it.joinToString(" + ")) }
}

/** "Completed & Skipped" — a table, a row opens its details (`TopUpToDoPage.jsx:469-552`). */
@Composable
private fun DoneBlock(state: CardUiState, rows: List<CardTopUp>, onEvent: (CardEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(modifier = Modifier.fillMaxWidth().insightCard(colors.surface, colors.border)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken)
                .padding(horizontal = 18.dp, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(ZillitIcons.Check, tint = colors.textMuted, size = 17.dp)
            InsightEyebrow(str(S.ah_completed_skipped_header))
            InsightTag(rows.size.toString(), colors.surfaceHover, colors.textMuted)
        }
        ZillitDataTable(
            rows = rows,
            columns = doneColumns(state),
            key = { it.id },
            onRowClick = { onEvent(InsightsEvent.OpenTopUpDetail(it.id)) },
            virtualised = false,
        )
    }
}

@Suppress("MagicNumber") // Column proportions.
private fun doneColumns(state: CardUiState): List<TableColumn<CardTopUp>> = listOf(
    TableColumn(header = str(S.desktop_card_card_holder), width = ColumnWidth.Weight(2f)) { row ->
        Column {
            ZillitText(
                text = state.personName(row.holderId, row.holderName),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            designation(state, row.holderId)?.let {
                ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
            }
        }
    },
    textColumn(str(S.ah_my_cards), muted = true) { "••••${it.cardLastFour ?: "0000"}" },
    textColumn(str(S.desktop_issued), numeric = true) { money(it.issuedAmount ?: it.amount, it.currency) },
    textColumn(str(S.desktop_method), muted = true) { methodTitle(it.method) },
    TableColumn(header = str(S.status)) { row -> DoneStatus(row.status) },
    textColumn(str(S.date), muted = true) { EpochDate.date(it.createdAt).ifEmpty { "—" } },
)

@Composable
private fun DoneStatus(status: String) {
    val colors = ZillitTheme.colors
    val (label, tint) = when (status) {
        "completed" -> str(S.completed) to colors.teal
        "partial" -> str(S.ah_status_partial) to colors.warning
        else -> str(S.ah_skipped_toast) to colors.textMuted
    }
    InsightTag(label, Color.Transparent, tint, dot = true)
}

private fun methodTitle(method: String?): String = when (method) {
    RESTORE -> str(S.desktop_ce_insights_restore_float_title)
    EXPENSE -> str(S.desktop_ce_insights_expense_amount_title)
    null, "" -> str(S.desktop_ce_insights_expense_amount_title)
    else -> method
}

/**
 * The id a pending request's unread is filed under — the web's `entityKeyOf`
 * (`TopUpToDoPage.jsx:42-44`): its entity, else its card, its holder, itself.
 */
private val CardTopUp.badgeKey: String
    get() = listOf(entityId, cardId, holderId).firstOrNull { !it.isNullOrBlank() } ?: id

private fun holderName(state: CardUiState, topUp: CardTopUp): String =
    state.personName(topUp.holderId, topUp.holderName).takeIf { it != "—" } ?: str(S.desktop_unknown)

private fun designation(state: CardUiState, userId: String?): String? =
    state.people.firstOrNull { it.id == userId }?.designation?.takeIf { it.isNotBlank() }

// -- the page's dialogs --------------------------------------------------------

@Composable
private fun TopUpDialogs(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    PartialDialog(state, onEvent)
    DetailDialog(state, onEvent)
    HistoryDialog(state, onEvent)
    LimitDialog(state, onEvent)
}

/** "Partial Top-Up": the amount (pre-filled) and a required note (`TopUpToDoPage.jsx:586-657`). */
@Composable
private fun PartialDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.insights.partial ?: return
    val currency = state.topUps.firstOrNull { it.id == draft.topUpId }?.currency
    ZillitDialogShell(
        title = str(S.ah_partial_topup),
        icon = ZillitIcons.Edit,
        visible = true,
        width = PARTIAL_WIDTH,
        onDismiss = { onEvent(InsightsEvent.ClosePartial) },
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InsightsEvent.ClosePartial) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = if (draft.submitting) {
                    str(S.desktop_ce_cards_submitting)
                } else {
                    str(S.desktop_pc_submit_partial_topup)
                },
                onClick = { onEvent(InsightsEvent.SubmitPartial) },
                enabled = draft.note.isNotBlank() && !draft.submitting,
                loading = draft.submitting,
            )
        },
    ) {
        CardCalcInput(
            value = draft.amount,
            onValueChange = { onEvent(InsightsEvent.EditPartial(it, draft.note)) },
            label = str(S.desktop_ce_insights_topup_amount_title),
            suffix = Money.symbol(currency).trim(),
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = draft.note,
            onValueChange = { onEvent(InsightsEvent.EditPartial(draft.amount, it)) },
            label = "${str(S.note_label)} *",
            placeholder = str(S.desktop_ce_insights_reason_partial),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "Top-Up Details" for a done row (`TopUpToDoPage.jsx:659-744`). */
@Suppress("LongMethod") // A grid of eight labelled facts.
@Composable
private fun DetailDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val topUp = state.topUps.firstOrNull { it.id == state.insights.topUpDetailId } ?: return
    val name = state.personName(topUp.holderId, topUp.holderName)
    val facts = buildList {
        add(Triple(str(S.desktop_card_card_holder), name, designation(state, topUp.holderId)))
        add(Triple(str(S.ah_my_cards), "···· ${topUp.cardLastFour ?: "0000"}", null))
        add(Triple(str(S.amount), money(topUp.amount, topUp.currency), null))
        add(Triple(str(S.desktop_method), methodTitle(topUp.method), null))
        add(Triple(str(S.status), topUp.status.replaceFirstChar { it.uppercase() }, null))
        add(Triple(str(S.date), EpochDate.date(topUp.createdAt).ifEmpty { "—" }, null))
        topUp.bsControlCode?.let { add(Triple(str(S.desktop_ce_insights_bs_control), it, null)) }
        topUp.receiptMerchant?.let { merchant ->
            add(
                Triple(
                    str(S.desktop_ce_insights_source_receipt),
                    merchant,
                    topUp.receiptAmount?.let { money(it, topUp.currency) },
                ),
            )
        }
    }
    ZillitDialogShell(
        title = str(S.ah_topup_details_title),
        icon = ZillitIcons.CreditCard,
        visible = true,
        width = DETAIL_WIDTH,
        onDismiss = { onEvent(InsightsEvent.OpenTopUpDetail(null)) },
        actions = {
            ZillitButton(
                text = str(S.history),
                onClick = { onEvent(InsightsEvent.OpenHistory(topUp.id, name.takeIf { it != "—" }.orEmpty())) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Clock,
            )
        },
    ) {
        facts.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                pair.forEach { (label, value, sub) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                        InsightEyebrow(label)
                        ZillitText(text = value, style = ZillitTheme.typography.bodyMedium)
                        sub?.let {
                            ZillitText(
                                text = it,
                                style = ZillitTheme.typography.labelSmall,
                                color = ZillitTheme.colors.textSecondary,
                            )
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        topUp.note?.let { note ->
            InsightEyebrow(str(S.note_label))
            ZillitText(text = "\"$note\"", style = ZillitTheme.typography.bodySmall)
        }
    }
}

/** "Top-Up History", the holder's name under it (`HistoryPanel`). */
@Composable
private fun HistoryDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val history = state.insights.history ?: return
    ZillitDialogShell(
        title = str(S.desktop_ce_insights_topup_history),
        subtitle = history.holderName.takeIf { it.isNotBlank() },
        icon = ZillitIcons.Clock,
        visible = true,
        width = DETAIL_WIDTH,
        onDismiss = { onEvent(InsightsEvent.CloseHistory) },
    ) {
        when {
            history.loading -> Row(Modifier.fillMaxWidth(), Arrangement.Center) { ZillitSpinner() }
            history.entries.isEmpty() -> ZillitText(
                text = str(S.desktop_card_no_request_history),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )

            else -> history.entries.forEachIndexed { index, entry ->
                if (index > 0) ZillitDivider()
                Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                    ZillitText(
                        text = entry.action.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    ZillitText(
                        text = listOfNotNull(
                            entry.userId?.let { state.personName(it) }?.takeIf { it != "—" },
                            EpochDate.dateTime(entry.at).takeIf { it.isNotEmpty() },
                        ).joinToString(" · "),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                    entry.note?.takeIf { it.isNotBlank() }?.let {
                        ZillitText(text = it, style = ZillitTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** "Top-up would exceed the card limit" — both buttons dismiss (`TopUpToDoPage.jsx:270-297`). */
@Composable
private fun LimitDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val alert = state.insights.limitAlert ?: return
    val fmt = { value: Double -> money(value, alert.currency) }
    ZillitDialogShell(
        title = str(S.desktop_ce_insights_limit_title),
        icon = ZillitIcons.Warning,
        visible = true,
        width = PARTIAL_WIDTH,
        onDismiss = { onEvent(InsightsEvent.DismissLimitAlert) },
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(InsightsEvent.DismissLimitAlert) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(text = str(S.ah_ok), onClick = { onEvent(InsightsEvent.DismissLimitAlert) })
        },
    ) {
        val message = str(
            S.desktop_ce_insights_limit_message,
            fmt(alert.balance),
            fmt(alert.limit),
            fmt(alert.amount),
            fmt(alert.after),
            fmt(alert.over),
        )
        val tail = if (alert.headroom > 0) {
            str(S.desktop_ce_insights_limit_headroom, fmt(alert.headroom))
        } else {
            str(S.desktop_ce_insights_limit_full)
        }
        ZillitText(text = "$message $tail", style = ZillitTheme.typography.bodyMedium)
    }
}

private const val RESTORE = "restore"
private const val EXPENSE = "expense"
private val PARTIAL_WIDTH = 440.dp
private val DETAIL_WIDTH = 520.dp

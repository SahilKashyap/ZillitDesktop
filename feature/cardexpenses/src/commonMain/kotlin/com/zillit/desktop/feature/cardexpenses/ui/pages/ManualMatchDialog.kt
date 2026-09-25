package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.MatchCandidate
import com.zillit.desktop.feature.cardexpenses.domain.inboxBadge
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.InboxEvent
import com.zillit.desktop.feature.cardexpenses.ui.WorkflowStatusPill
import com.zillit.desktop.feature.cardexpenses.ui.components.FieldGroupLabel
import com.zillit.desktop.feature.cardexpenses.ui.components.ReconciliationBadgePill
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * Manual Match (`ui/ManualMatchModal.jsx`): the receipt in three columns, the
 * card transactions it could be, and Confirm Match on the one picked.
 *
 * A row click selects and a second click clears, as the web's does; the
 * footer counts what the server offered, not what is selected.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // The modal, top to bottom.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ManualMatchDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val match = state.inbox.manualMatch ?: return
    val receipt = match.receipt
    val holder = state.people.firstOrNull { it.id == receipt.holderId }
    ZillitDialogShell(
        title = str(S.desktop_manual_match),
        icon = ZillitIcons.Link,
        visible = true,
        width = DIALOG_WIDTH,
        scrollable = false,
        onDismiss = { onEvent(InboxEvent.CloseManualMatch) },
        actions = {
            ZillitText(
                text = str(
                    if (match.candidates.size == 1) {
                        S.desktop_ce_inbox_candidates_one
                    } else {
                        S.desktop_ce_inbox_candidates_many
                    },
                    match.candidates.size,
                ),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = if (match.confirming) {
                    str(S.desktop_ce_inbox_matching_dots)
                } else {
                    str(S.desktop_ce_inbox_confirm_match)
                },
                onClick = { onEvent(InboxEvent.ConfirmManualMatch) },
                leadingIcon = ZillitIcons.Link,
                enabled = match.selectedId != null && !match.confirming,
            )
        },
    ) {
        // The receipt, three across (`ManualMatchModal.jsx:83-141`).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surfaceSunken)
                .padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                Column(Modifier.weight(1f)) {
                    FieldGroupLabel(str(S.ah_merchant))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitText(receipt.description.ifBlank { EM_DASH }, style = ZillitTheme.typography.bodySmall)
                        receipt.matchScore?.let { ScorePill(it) }
                    }
                }
                Column(Modifier.weight(1f)) {
                    FieldGroupLabel(str(S.amount))
                    ZillitText(
                        text = money(receipt.amount, receipt.currency),
                        style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = ZillitTheme.colors.accent,
                    )
                }
                Column(Modifier.weight(1f)) {
                    FieldGroupLabel(str(S.date))
                    ZillitText(date(receipt.date), style = ZillitTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                Column(Modifier.weight(1f)) {
                    FieldGroupLabel(str(S.desktop_card_card_holder))
                    ZillitText(
                        text = holder
                            ?.let { listOf(it.name, it.designation).filter(String::isNotBlank).joinToString(" · ") }
                            ?: EM_DASH,
                        style = ZillitTheme.typography.bodySmall,
                    )
                }
                Column(Modifier.weight(1f)) {
                    FieldGroupLabel(str(S.desktop_card_cost_code))
                    ZillitText(
                        text = receipt.nominalCode?.takeIf { it.isNotBlank() } ?: EM_DASH,
                        style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = ZillitTheme.colors.gold,
                    )
                }
                Column(Modifier.weight(1f)) {
                    FieldGroupLabel(str(S.status))
                    receipt.inboxBadge?.let { ReconciliationBadgePill(it) } ?: WorkflowStatusPill(receipt.status)
                }
            }
            if (!receipt.transactionId.isNullOrBlank()) {
                Column {
                    FieldGroupLabel(str(S.desktop_ce_inbox_linked_transaction))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                        val parts = listOfNotNull(
                            receipt.transactionMerchant?.takeIf { it.isNotBlank() } ?: receipt.description,
                            holder?.name,
                            receipt.transactionAmount?.let { money(it, receipt.currency) },
                            receipt.transactionCardLastFour?.takeIf { it.isNotBlank() }?.let { "···· $it" },
                            receipt.transactionDate?.let { date(it) },
                        )
                        ZillitText(
                            text = parts.joinToString(" · "),
                            style = ZillitTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }
        ZillitDivider()
        Box(modifier = Modifier.fillMaxWidth().height(CANDIDATES_HEIGHT)) {
            when {
                match.loading -> MatchNote(str(S.desktop_ce_inbox_loading_candidates))
                match.candidates.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ZillitIcon(ZillitIcons.Search, size = EMPTY_GLYPH, tint = ZillitTheme.colors.borderStrong)
                    ZillitText(
                        text = str(S.desktop_ce_inbox_no_candidates),
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }

                else -> Column(Modifier.fillMaxSize()) {
                    CandidateHeader()
                    ZillitScrollColumn(modifier = Modifier.fillMaxSize()) {
                        match.candidates.forEach { candidate ->
                            CandidateRow(candidate, candidate.id == match.selectedId) {
                                onEvent(InboxEvent.SelectCandidate(candidate.id))
                            }
                            ZillitDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
    ) {
        HeaderCell(str(S.date), Modifier.width(DATE_COLUMN))
        HeaderCell(str(S.ah_merchant), Modifier.weight(1f))
        HeaderCell(str(S.ah_my_cards), Modifier.width(CARD_COLUMN))
        HeaderCell(str(S.amount), Modifier.width(AMOUNT_COLUMN), TextAlign.End)
        HeaderCell(str(S.desktop_ce_inbox_score), Modifier.width(SCORE_COLUMN), TextAlign.End)
        Spacer(Modifier.width(SELECTED_COLUMN))
    }
}

@Composable
private fun CandidateRow(candidate: MatchCandidate, selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.accentSoft else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            date(candidate.date),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            modifier = Modifier.width(DATE_COLUMN),
        )
        ZillitText(
            candidate.merchant.ifBlank { EM_DASH },
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            candidate.cardLastFour?.let { "•••• $it" } ?: EM_DASH,
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            modifier = Modifier.width(CARD_COLUMN),
        )
        ZillitText(
            money(candidate.amount, candidate.currency),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.accent,
            textAlign = TextAlign.End,
            modifier = Modifier.width(AMOUNT_COLUMN),
        )
        Box(Modifier.width(SCORE_COLUMN), contentAlignment = Alignment.CenterEnd) {
            ScorePill(candidate.scorePercent, showZero = true)
        }
        Box(Modifier.width(SELECTED_COLUMN), contentAlignment = Alignment.CenterEnd) {
            if (selected) {
                ZillitText(
                    str(S.selected),
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.accent,
                )
            }
        }
    }
}

/** A match score, green from 80, amber from 50, grey below (`ManualMatchModal.jsx:90, 167-168`). */
@Composable
internal fun ScorePill(percent: Int, showZero: Boolean = false) {
    if (percent <= 0 && !showZero) return
    ZillitStatusPill(
        label = "$percent%",
        tone = when {
            percent >= STRONG -> StatusTone.Ready
            percent >= FAIR -> StatusTone.Pending
            else -> StatusTone.Neutral
        },
    )
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier, align: TextAlign? = null) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textSecondary,
        textAlign = align,
        modifier = modifier,
    )
}

@Composable
private fun MatchNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(text, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textSecondary)
    }
}

private const val STRONG = 80
private const val FAIR = 50
private val DIALOG_WIDTH = 720.dp
private val CANDIDATES_HEIGHT = 320.dp
private val EMPTY_GLYPH = 26.dp
private val DATE_COLUMN = 90.dp
private val CARD_COLUMN = 80.dp
private val AMOUNT_COLUMN = 96.dp
private val SCORE_COLUMN = 64.dp
private val SELECTED_COLUMN = 64.dp

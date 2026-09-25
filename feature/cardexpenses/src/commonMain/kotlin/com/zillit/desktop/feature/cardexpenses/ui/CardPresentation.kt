package com.zillit.desktop.feature.cardexpenses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.AlertSeverity
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus

/** How the card domain is rendered. One mapping, read by every screen. */

val CardStatus.tone: StatusTone
    get() = when (this) {
        CardStatus.Active, CardStatus.DigitalActive -> StatusTone.Done
        CardStatus.Approved, CardStatus.Override -> StatusTone.Ready
        CardStatus.Requested, CardStatus.Pending -> StatusTone.Pending
        CardStatus.InTransit -> StatusTone.InTransit
        CardStatus.Rejected -> StatusTone.Rejected
        CardStatus.Suspended, CardStatus.Cancelled -> StatusTone.Escalated
        CardStatus.Closed, CardStatus.Unknown -> StatusTone.Neutral
    }

val CardWorkflowStatus.tone: StatusTone
    get() = when (this) {
        CardWorkflowStatus.Posted -> StatusTone.Done
        CardWorkflowStatus.Approved, CardWorkflowStatus.ReadyToPost -> StatusTone.Ready
        CardWorkflowStatus.Submitted,
        CardWorkflowStatus.AwaitingApproval,
        CardWorkflowStatus.PendingCode,
        CardWorkflowStatus.Processing,
        -> StatusTone.Progress

        CardWorkflowStatus.Imported, CardWorkflowStatus.PendingReceipt, CardWorkflowStatus.New -> StatusTone.Pending
        CardWorkflowStatus.InApproval -> StatusTone.Progress
        CardWorkflowStatus.Queried, CardWorkflowStatus.Rejected, CardWorkflowStatus.Personal -> StatusTone.Rejected
        CardWorkflowStatus.Overridden, CardWorkflowStatus.Escalated, CardWorkflowStatus.Duplicate ->
            StatusTone.Escalated
        CardWorkflowStatus.UnderReview -> StatusTone.Progress
        CardWorkflowStatus.Unknown -> StatusTone.Neutral
    }

val AlertSeverity.tone: StatusTone
    get() = when (this) {
        AlertSeverity.High -> StatusTone.Rejected
        AlertSeverity.Medium -> StatusTone.Pending
        AlertSeverity.Low -> StatusTone.Progress
    }

@Composable
fun CardStatusPill(status: CardStatus, modifier: Modifier = Modifier) {
    ZillitStatusPill(label = status.label, tone = status.tone, dot = true, modifier = modifier)
}

@Composable
fun WorkflowStatusPill(status: CardWorkflowStatus, modifier: Modifier = Modifier) {
    ZillitStatusPill(label = status.label, tone = status.tone, dot = true, modifier = modifier)
}

/**
 * The reconciliation badge, where it overrides the workflow one.
 *
 * Falls back to the workflow status when the receipt has a document — see
 * `CardReceipt.reconciliationLabel` for why the two vocabularies swap.
 */
@Composable
fun ReconciliationPill(
    reconciliation: String?,
    workflow: CardWorkflowStatus,
    modifier: Modifier = Modifier,
) {
    when (reconciliation) {
        null -> WorkflowStatusPill(workflow, modifier)
        str(S.desktop_reconciled) ->
            ZillitStatusPill(str(S.desktop_reconciled), modifier, StatusTone.Done, dot = true)
        else -> ZillitStatusPill(reconciliation, modifier, StatusTone.Pending, dot = true)
    }
}

@Composable
fun MatchStatusPill(status: MatchStatus, score: Int?, modifier: Modifier = Modifier) {
    ZillitStatusPill(
        label = when {
            status == MatchStatus.Suggested -> status.label
            status == MatchStatus.Matched && score != null -> str(S.desktop_card_matched_score, score)
            status == MatchStatus.Matched -> str(S.desktop_matched)
            else -> str(S.desktop_no_match)
        },
        tone = if (status == MatchStatus.Matched) StatusTone.Done else StatusTone.Pending,
        dot = true,
        modifier = modifier,
    )
}

fun money(amount: Double?, currency: String?): String = Money.format(amount, currency)

fun date(millis: Long?): String = EpochDate.date(millis).ifEmpty { "—" }

/** `•••• 4821`, the way a card is named everywhere it appears. */
fun cardLabel(card: ExpenseCard): String =
    card.lastFour?.takeIf { it.isNotBlank() }?.let { "•••• $it" }
        ?: card.issuer?.takeIf { it.readsAsAName() }
        ?: str(S.ah_my_cards)

/**
 * Whether a string is something to show a person, or a key to look one up by.
 *
 * `card_issuer` holds a provider **id** on this production's cards — seen live
 * as `fd82c1a1-d819-458a-8ed7-…` printed under the card number — so a value
 * that is only hex and dashes is not an issuer name and must not be drawn as
 * one. An identifier on screen is meaningless to everyone who sees it and
 * reads as corruption.
 */
internal fun String.readsAsAName(): Boolean {
    val value = trim()
    if (value.isEmpty()) return false
    if (value.length < IDENTIFIER_LENGTH) return true
    return !value.all { it.isHexOrDash() }
}

private fun Char.isHexOrDash(): Boolean = isDigit() || this in HEX_LETTERS || this == '-'

/**
 * A card drawn as a card.
 *
 * Not decoration: a cardholder identifies their card by its face, and a row in
 * a table does not read as "the thing in my wallet". The limit meter is the
 * second reason — it answers "can I still spend on this" without arithmetic.
 */
@Composable
fun CardFace(
    card: ExpenseCard,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** The issuer's name, where the card carries only its id. */
    issuer: String? = null,
    /** The holder's name, resolved through the crew; see `CardUiState.holderName`. */
    holder: String? = null,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(if (card.status.tone == StatusTone.Done) colors.surfaceSelected else colors.surfaceSunken)
            .border(HAIRLINE, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitIcon(ZillitIcons.CreditCard, tint = colors.accent)
            Spacer(Modifier.width(ZillitTheme.spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(text = cardLabel(card), style = ZillitTheme.typography.titleSmall, maxLines = 1)
                ZillitText(
                    text = listOfNotNull(
                        issuer?.takeIf { it.isNotBlank() }
                            ?: card.issuer?.takeIf { it.readsAsAName() },
                        card.type.label,
                    ).joinToString(" · "),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
            CardStatusPill(card.status)
        }

        if (!compact) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(
                        text = money(card.balance ?: card.limit, card.currency),
                        style = ZillitTheme.typography.titleLarge,
                    )
                    ZillitText(
                        text = str(S.desktop_card_available_of, money(card.limit, card.currency)),
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                ZillitText(
                    text = holder?.takeIf { it.isNotBlank() && it != "—" }
                        ?: card.holderName.ifBlank { str(S.unassigned) },
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
            ZillitMeter(
                fraction = card.consumedFraction,
                tone = if (card.consumedFraction > NEARLY_SPENT) StatusTone.Rejected else StatusTone.Ready,
                modifier = Modifier.fillMaxWidth().height(METER_HEIGHT),
            )
        }
    }
}

private const val IDENTIFIER_LENGTH = 12
private val HEX_LETTERS = 'a'..'f'
private const val NEARLY_SPENT = 0.85f
private val HAIRLINE = 1.dp
private val METER_HEIGHT = 7.dp

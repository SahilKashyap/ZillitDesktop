package com.zillit.desktop.feature.cardexpenses.ui.pages

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.CrewRules
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.CrewEvent
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * Card Extension — the cardholder's top-ups (`CardExtensionPage.jsx`,
 * `TopUpExtensionPanel.jsx`).
 *
 * One "Top-ups" panel over the chosen active card: every top-up raised
 * against it, newest first, each with its trail, and Request Top-up. No
 * balance hero — the web's panel has none, and a balance beside a list of
 * requests invites reading one as the sum of the other.
 */
@Suppress("LongMethod") // One panel, read top to bottom.
@Composable
fun CardExtensionPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val cards = CrewRules.toppableCards(state.cards)
    val selected = cards.firstOrNull { it.id == state.crew.topUpCardId } ?: cards.firstOrNull()

    CrewScrollPage {
        if (selected == null) {
            if (!state.loading) {
                CrewTile(padding = ZillitTheme.spacing.xxl) {
                    ZillitText(
                        text = str(S.desktop_ce_crew_no_active_card_project),
                        style = ZillitTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ZillitText(
                        text = str(S.desktop_ce_crew_topups_need_card),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            return@CrewScrollPage
        }

        CrewTile(padding = ZillitTheme.spacing.none) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                ZillitText(text = str(S.desktop_ce_crew_topups), style = ZillitTheme.typography.titleSmall)
                if (cards.size > 1) {
                    ZillitSelect(
                        value = selected,
                        options = cards,
                        onSelect = { onEvent(CrewEvent.SelectTopUpCard(it.id)) },
                        label = ::topUpCardLabel,
                        modifier = Modifier.width(SELECT_WIDTH),
                    )
                } else {
                    ZillitText(
                        text = topUpCardLabel(selected),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                Spacer(Modifier.weight(1f))
                ZillitButton(
                    text = str(S.desktop_card_request_top_up),
                    onClick = { onEvent(CrewEvent.OpenTopUpRequest(true)) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
            ZillitDivider()
            when {
                state.loading && state.topUps.isEmpty() -> CenteredNote(str(S.ah_loading_topups))
                state.crew.topUpsFailed -> Row(
                    modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = str(S.desktop_pc_couldnt_load_topups),
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.warning,
                    )
                    ZillitButton(
                        text = str(S.retry),
                        onClick = { onEvent(CardEvent.Refresh) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }

                state.topUps.isEmpty() -> CenteredNote(str(S.desktop_pc_no_topups_yet))
                else -> state.topUps.forEachIndexed { index, topUp ->
                    if (index > 0) ZillitDivider()
                    TopUpRow(topUp, selected, onEvent)
                }
            }
        }
    }
}

@Composable
private fun TopUpRow(topUp: CardTopUp, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitText(
            text = money(topUp.amount, topUp.currency ?: card.currency),
            style = ZillitTheme.typography.titleSmall,
        )
        ZillitStatusPill(label = topUpStatusLabel(topUp.status), tone = topUpTone(topUp.status), dot = true)
        topUp.method?.takeIf { it.isNotBlank() }?.let {
            ZillitText(
                text = it.uppercase(),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        Spacer(Modifier.weight(1f))
        ZillitText(
            text = date(topUp.createdAt),
            style = ZillitTheme.typography.numeric,
            color = ZillitTheme.colors.textSecondary,
        )
        if (topUp.trail.isNotEmpty()) {
            ZillitIconButton(
                icon = ZillitIcons.Clock,
                contentDescription = str(S.history),
                onClick = { onEvent(CrewEvent.OpenTopUpTrail(topUp.id)) },
            )
        }
    }
}

/** `Card •••• 4821`, the web's entity label. */
private fun topUpCardLabel(card: ExpenseCard): String =
    str(S.desktop_ce_crew_card_label, card.lastFour?.takeIf { it.isNotBlank() } ?: "0000")

/** The status word capitalised, as the web prints it. */
private fun topUpStatusLabel(status: String): String = when (status) {
    "pending" -> str(S.pending)
    "completed" -> str(S.ah_status_completed)
    "partial" -> str(S.ah_status_partial)
    "skipped" -> str(S.ah_status_skipped)
    "" -> "—"
    else -> status.replaceFirstChar { it.uppercase() }
}

private fun topUpTone(status: String): StatusTone = when (status) {
    "pending" -> StatusTone.Pending
    "completed" -> StatusTone.Done
    "partial" -> StatusTone.Progress
    else -> StatusTone.Neutral
}

/**
 * Request Top-up — amount and reason, both required (ZL-20808). The card's
 * currency sits beside the amount and is never sent.
 */
@Suppress("LongMethod") // One dialog, read top to bottom.
@Composable
internal fun TopUpRequestDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.crew.topUpRequest
    val card = state.cards.firstOrNull { it.id == draft?.cardId }
    ZillitDialogShell(
        title = str(S.desktop_card_request_top_up),
        visible = draft != null,
        onDismiss = { onEvent(CrewEvent.OpenTopUpRequest(false)) },
        icon = ZillitIcons.Wallet,
        width = REQUEST_WIDTH,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(CrewEvent.OpenTopUpRequest(false)) },
                variant = ButtonVariant.Tertiary,
                enabled = draft?.sending != true,
            )
            ZillitButton(
                text = str(
                    if (draft?.sending == true) S.desktop_ce_crew_requesting else S.desktop_ce_crew_send_request,
                ),
                onClick = { onEvent(CrewEvent.SendTopUpRequest) },
                enabled = draft?.canSend == true,
                loading = draft?.sending == true,
            )
        },
    ) {
        val open = draft ?: return@ZillitDialogShell
        ZillitText(
            text = str(S.desktop_pc_request_topup_intro, card?.let(::topUpCardLabel) ?: ""),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitTextField(
            value = open.amount,
            onValueChange = { onEvent(CrewEvent.EditTopUpRequest(open.copy(amount = it.crewAmount()))) },
            label = str(S.amount) + " *",
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            enabled = !open.sending,
            trailingContent = card?.currency?.takeIf { it.isNotBlank() }?.let { code ->
                {
                    ZillitText(
                        text = code,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = open.reason,
            onValueChange = { onEvent(CrewEvent.EditTopUpRequest(open.copy(reason = it))) },
            label = str(S.reason) + " *",
            placeholder = str(S.desktop_pc_eg_fuel_run),
            singleLine = false,
            enabled = !open.sending,
            modifier = Modifier.fillMaxWidth().height(REASON_HEIGHT),
        )
    }
}

/** Top-up History — the row's own trail, reason and amount on the note line. */
@Composable
internal fun TopUpTrailDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val topUp = state.topUps.firstOrNull { it.id == state.crew.topUpTrailId }
    ZillitDialogShell(
        title = str(S.desktop_pc_topup_history),
        subtitle = topUp?.let { "${money(it.amount, it.currency)} · ${date(it.createdAt)}" },
        visible = topUp != null,
        onDismiss = { onEvent(CrewEvent.OpenTopUpTrail(null)) },
        icon = ZillitIcons.Clock,
        width = REQUEST_WIDTH,
    ) {
        val open = topUp ?: return@ZillitDialogShell
        open.trail.forEach { step ->
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                ZillitText(
                    text = step.action.replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "—" },
                    style = ZillitTheme.typography.bodyMedium,
                )
                ZillitText(
                    text = listOfNotNull(
                        state.personName(step.userId).takeIf { step.userId != null },
                        date(step.at).takeIf { it != "—" },
                    ).joinToString(" · ").ifBlank { "—" },
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                val note = listOfNotNull(
                    step.reason?.takeIf { it.isNotBlank() },
                    step.amount?.let { money(it, open.currency) },
                ).joinToString(" · ")
                if (note.isNotBlank()) {
                    ZillitText(
                        text = note,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
        }
    }
}

private val SELECT_WIDTH = 220.dp
private val REQUEST_WIDTH = 440.dp
private val REASON_HEIGHT = 80.dp

package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.CrewEvent
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money
import com.zillit.desktop.feature.cardexpenses.ui.unreadRow

/**
 * The Coding Queue — receipts from the coordinator's departments waiting on
 * a code (`CodingQueuePage.jsx`). A grid of cards; a card opens Code Receipt.
 */
@Composable
fun CodingQueuePage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    LaunchedEffect(Unit) { onEvent(CrewEvent.Prime) }
    CrewScrollPage {
        when {
            state.loading && state.receipts.isEmpty() -> CenteredNote(str(S.ah_loading))
            state.receipts.isEmpty() -> CenteredNote(str(S.desktop_ce_crew_no_pending_coding))
            else -> CrewCardGrid(state.receipts, key = { it.id }) { receipt ->
                CodingCard(state, receipt, onEvent)
            }
        }
    }
}

@Composable
private fun CodingCard(state: CardUiState, receipt: CardReceipt, onEvent: (CardEvent) -> Unit) {
    val holder = state.people.firstOrNull { it.id == receipt.holderId }
    CrewTile(onClick = { onEvent(CrewEvent.OpenCode(receipt.id)) }, padding = ZillitTheme.spacing.md) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = receipt.description.ifBlank { "—" },
                style = ZillitTheme.typography.label,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            // The web chips coding_queue only (`CodingQueuePage.jsx:159`); opening the
            // card still reads both (`:66-81`).
            ZillitBadge(count = state.unreadRow("coding_queue", receipt.id))
            CrewStatusPill(receipt)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = if (receipt.amount != 0.0) money(receipt.amount, receipt.currency) else "—",
                style = ZillitTheme.typography.titleSmall,
                color = ZillitTheme.colors.accentText,
            )
            ZillitText(text = date(receipt.date), style = ZillitTheme.typography.labelSmall)
        }
        if (holder != null && holder.name.isNotBlank()) {
            ZillitText(
                text = listOf(holder.name, holder.department).filter { it.isNotBlank() }.joinToString(" · "),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * Code Receipt (`CodingQueuePage.jsx:184-271`): the document, the receipt's
 * figures read-only, and the coding — Save Draft, and one of Approve &
 * Submit (for an approver) or Submit for Approval.
 */
@Suppress("LongMethod") // One dialog, read top to bottom.
@Composable
internal fun CodeReceiptDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.crew.code
    ZillitDialogShell(
        title = str(S.desktop_ce_crew_code_receipt),
        visible = draft != null,
        onDismiss = { onEvent(CrewEvent.OpenCode(null)) },
        icon = ZillitIcons.Receipt,
        width = CODE_WIDTH,
        actions = {
            val saving = draft?.saving == true
            val coded = draft?.costCode?.isNotBlank() == true
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.ah_save_draft),
                onClick = { onEvent(CrewEvent.SaveCodeDraft) },
                variant = ButtonVariant.Secondary,
                enabled = !saving,
            )
            if (state.viewer.isApprover) {
                ZillitButton(
                    text = if (saving) str(S.ah_saving) else str(S.desktop_ce_crew_approve_submit),
                    onClick = { onEvent(CrewEvent.ApproveAndSubmitCode) },
                    enabled = coded && !saving,
                )
            } else {
                ZillitButton(
                    text = if (saving) str(S.ah_saving) else str(S.dm_action_submit),
                    onClick = { onEvent(CrewEvent.SubmitCode) },
                    enabled = coded && !saving,
                )
            }
        },
    ) {
        val open = draft ?: return@ZillitDialogShell
        val receipt = open.receipt
        val holder = state.people.firstOrNull { it.id == receipt.holderId }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            CrewStatusPill(receipt)
            if (receipt.urgent) UrgentPill()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            ReceiptFilePane(receipt, onEvent, Modifier.width(PANE_WIDTH).heightIn(min = PANE_HEIGHT))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                    CrewField(str(S.ah_merchant), receipt.description.ifBlank { "—" }, Modifier.weight(1f))
                    CrewField(
                        str(S.amount),
                        if (receipt.amount != 0.0) money(receipt.amount, receipt.currency) else "—",
                        Modifier.weight(1f),
                        emphasised = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                    CrewField(str(S.date), date(receipt.date), Modifier.weight(1f))
                    CrewField(
                        str(S.desktop_card_card_holder),
                        listOfNotNull(holder?.name ?: "—", holder?.designation?.takeIf { it.isNotBlank() })
                            .joinToString(" · "),
                        Modifier.weight(1f),
                    )
                }
                ZillitDivider()
                CrewField(str(S.ah_budget_coding), "")
                CrewCodeField(
                    value = open.costCode,
                    onValueChange = { onEvent(CrewEvent.EditCode(open.copy(costCode = it))) },
                    nominals = state.crew.nominals,
                    label = str(S.desktop_card_cost_code) + " *",
                    placeholder = str(S.desktop_ce_crew_enter_code),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                    if (state.crew.isTelevision) {
                        ZillitTextField(
                            value = open.episode,
                            onValueChange = { onEvent(CrewEvent.EditCode(open.copy(episode = it))) },
                            label = str(S.episode),
                            placeholder = str(S.ah_episode_hint),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ZillitTextField(
                        value = open.description,
                        onValueChange = { onEvent(CrewEvent.EditCode(open.copy(description = it))) },
                        label = str(S.description),
                        placeholder = str(S.ah_coding_description_hint),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private val CODE_WIDTH = 780.dp
private val PANE_WIDTH = 260.dp
private val PANE_HEIGHT = 300.dp

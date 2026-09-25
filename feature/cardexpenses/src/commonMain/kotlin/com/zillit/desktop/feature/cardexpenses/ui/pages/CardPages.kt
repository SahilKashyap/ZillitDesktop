package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.ExportFormat
import com.zillit.desktop.feature.cardexpenses.ui.CardAmountAction
import com.zillit.desktop.feature.cardexpenses.ui.CardConfirmAction
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardPrompt
import com.zillit.desktop.feature.cardexpenses.ui.CardStatusPill
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.CardsEvent
import com.zillit.desktop.feature.cardexpenses.ui.cardLabel
import com.zillit.desktop.feature.cardexpenses.ui.components.CardHistoryTrail
import com.zillit.desktop.feature.cardexpenses.ui.components.DetailPanePadding
import com.zillit.desktop.feature.cardexpenses.ui.components.FieldGroupLabel
import com.zillit.desktop.feature.cardexpenses.ui.components.PersonCell
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money

/** The standard page body. See the cash module's equivalent. */
@Composable
fun ScrollingPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ZillitScrollColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        content = content,
    )
}

/** For a page whose own content scrolls — a table, a queue. */
@Composable
fun FixedPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        content = content,
    )
}

/**
 * The export pair — the web's Export menu (PDF, Excel) as two small buttons.
 *
 * Two buttons rather than a menu because a menu here is one more click in
 * front of the one thing the control does.
 */
@Composable
internal fun ExportButtons(busy: Boolean, onExport: (ExportFormat) -> Unit) {
    ZillitButton(
        text = str(S.recce_export_pdf),
        onClick = { onExport(ExportFormat.Pdf) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Download,
        enabled = !busy,
        loading = busy,
    )
    ZillitButton(
        text = str(S.desktop_dm_export_excel),
        onClick = { onExport(ExportFormat.Excel) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        enabled = !busy,
    )
}

/**
 * The single action a card's status permits.
 *
 * A card lifecycle is linear — requested, pending, approved, active,
 * suspended — so one step per card is the shape of the thing. A pending
 * card's step belongs to whoever its chain's next tier names
 * (`adminUi.jsx:297-313`); anyone else sees Override, and only if they hold
 * the grant and the production allows it. Approve, Suspend and Reactivate act
 * at once, as on the web (`CardRegisterPage.jsx:390, 838-866`).
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // The card lifecycle table: one branch per status.
@Composable
fun CardPrimaryAction(state: CardUiState, card: ExpenseCard, onEvent: (CardEvent) -> Unit) {
    val step = state.cardApproval(card)
    val accountant = state.viewer.isAccountant
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        when {
            card.status == CardStatus.Pending && !step.canApprove && state.viewer.canOverrideCard -> ZillitButton(
                text = str(S.dm_nom_table_override),
                onClick = { onEvent(CardsEvent.AskOverride(card.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Shield,
                enabled = !state.busy,
            )

            card.status == CardStatus.Pending && step.canApprove -> {
                ZillitButton(
                    text = str(S.reject),
                    onClick = { onEvent(CardsEvent.AskReject(card.id)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                ZillitButton(
                    text = str(S.approve),
                    onClick = { onEvent(CardsEvent.Approve(card.id)) },
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                    loading = state.busy && state.cardsArea.actionCardId == card.id,
                )
            }

            (card.status == CardStatus.Approved || card.status == CardStatus.Override) && accountant -> ZillitButton(
                text = str(S.desktop_card_activate_assign_number),
                onClick = { onEvent(CardEvent.OpenActivation(card.id)) },
                size = ButtonSize.Small,
                enabled = !state.busy,
            )

            card.isDigitalActive && accountant -> ZillitButton(
                text = str(S.desktop_ce_cards_assign_physical),
                onClick = { onEvent(CardsEvent.AskAssignPhysical(card.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )

            card.status == CardStatus.Active && accountant -> ZillitButton(
                text = str(S.desktop_card_suspend),
                onClick = { onEvent(CardsEvent.Suspend(card.id)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )

            card.status == CardStatus.Suspended && accountant -> ZillitButton(
                text = str(S.desktop_card_reactivate),
                onClick = { onEvent(CardsEvent.Reactivate(card.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )

            else -> ZillitText(
                text = "—",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

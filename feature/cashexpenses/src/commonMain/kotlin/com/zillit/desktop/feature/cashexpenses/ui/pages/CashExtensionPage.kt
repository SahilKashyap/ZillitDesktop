package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUps
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.FundsAction
import com.zillit.desktop.feature.cashexpenses.ui.FundsDesk
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople

/**
 * Cash Extension — the crew member asking for more on a float they hold
 * (`CashExtensionPage.jsx` over `TopUpExtensionPanel.jsx`).
 *
 * Any float with cash in hand — collected, active or spending — can be topped
 * up; with more than one, a select picks which. The list is that float's
 * top-ups, as the accounts team left them, each with its history.
 */
@Suppress("LongMethod") // The no-float, loading, failed, empty and listed states, and both dialogs.
@Composable
fun CashExtensionPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val floats = FundsDesk.toppable(state.myFloats)
    val extension = state.fundsUi.extension
    val selected = floats.firstOrNull { it.id == extension.floatId } ?: floats.firstOrNull()
    var historyId by remember { mutableStateOf<String?>(null) }
    fun act(action: FundsAction) = onEvent(CashEvent.Funds(action))

    Box(Modifier.fillMaxSize()) {
        ScrollingPage {
            if (floats.isEmpty()) {
                FundsCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    ) {
                        ZillitText(
                            text = if (state.loading) {
                                str(S.ah_loading_topups)
                            } else {
                                str(S.desktop_pc_no_active_float_project)
                            },
                            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = ZillitTheme.colors.textSecondary,
                        )
                        if (!state.loading) {
                            ZillitText(
                                text = str(S.desktop_pc_topups_once_active),
                                style = ZillitTheme.typography.bodySmall,
                                color = ZillitTheme.colors.textMuted,
                            )
                        }
                    }
                }
                return@ScrollingPage
            }

            FundsCard(modifier = Modifier.fillMaxWidth()) {
                ExtensionHeader(state, floats, selected, ::act)
                ZillitDivider()
                when {
                    extension.loading || (state.loading && extension.rows.isEmpty()) ->
                        CentredLine(str(S.ah_loading_topups))

                    extension.failed -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitText(
                            text = str(S.desktop_pc_couldnt_load_topups),
                            style = ZillitTheme.typography.bodySmall,
                            color = ZillitTheme.colors.warning,
                        )
                        ZillitButton(
                            text = str(S.retry),
                            onClick = { act(FundsAction.RetryExtension) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                    }

                    extension.rows.isEmpty() -> CentredLine(str(S.desktop_pc_no_topups_yet))

                    else -> extension.rows.forEachIndexed { index, row ->
                        if (index > 0) ZillitDivider()
                        ExtensionRow(state, row, selected, onHistory = { historyId = row.id })
                    }
                }
            }
        }

        RequestTopUpDialog(state, selected, ::act)
        TopUpHistoryDialog(
            state = state,
            topUp = extension.rows.firstOrNull { it.id == historyId },
            fallbackCurrency = selected?.currency,
            onDismiss = { historyId = null },
        )
    }
}

@Composable
private fun ExtensionHeader(
    state: CashUiState,
    floats: List<CashFloat>,
    selected: CashFloat?,
    act: (FundsAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitText(
            text = str(S.ah_topups_tab),
            style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
        )
        if (floats.size > 1 && selected != null) {
            ZillitSelect(
                value = selected,
                options = floats,
                onSelect = { act(FundsAction.SelectExtensionFloat(it.id)) },
                label = ::floatLabel,
                modifier = Modifier.width(FLOAT_PICKER_WIDTH),
            )
        } else if (selected != null) {
            ZillitText(
                text = floatLabel(selected),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        Spacer(Modifier.weight(1f))
        ZillitButton(
            text = str(S.desktop_card_request_top_up),
            onClick = { act(FundsAction.OpenRequest) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Add,
            enabled = !state.busy && selected != null,
        )
    }
}

@Composable
private fun ExtensionRow(state: CashUiState, row: CashTopUp, float: CashFloat?, onHistory: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitText(
            text = state.formatMoney(row.amount, row.currency ?: float?.currency),
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
        )
        ZillitStatusPill(
            label = row.status.replaceFirstChar { it.uppercase() }.ifBlank { "—" },
            tone = when (row.status) {
                CashTopUps.COMPLETED -> StatusTone.Done
                CashTopUps.PARTIAL -> StatusTone.Progress
                CashTopUps.SKIPPED -> StatusTone.Neutral
                else -> StatusTone.Pending
            },
        )
        row.method?.takeIf(String::isNotBlank)?.let {
            ZillitText(
                text = it.uppercase(),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        Spacer(Modifier.weight(1f))
        ZillitText(
            text = EpochDate.date(row.createdAt).ifEmpty { "—" },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        if (row.history.isNotEmpty()) {
            ZillitIconButton(
                icon = ZillitIcons.Clock,
                contentDescription = str(S.desktop_pc_topup_history),
                onClick = onHistory,
            )
        }
    }
}

@Composable
private fun CentredLine(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
    )
}

/** Amount and reason, both required (ZL-20808); the float's currency is shown, never sent. */
@Composable
private fun RequestTopUpDialog(state: CashUiState, float: CashFloat?, act: (FundsAction) -> Unit) {
    val draft = state.fundsUi.request
    val amount = draft?.amount?.trim()?.toDoubleOrNull()
    val canSubmit = draft != null && amount != null && amount > 0 && draft.reason.isNotBlank() && !state.busy
    ZillitDialogShell(
        title = str(S.desktop_card_request_top_up),
        icon = ZillitIcons.Add,
        visible = draft != null,
        onDismiss = { if (!state.busy) act(FundsAction.CloseRequest) },
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.cancel),
                onClick = { act(FundsAction.CloseRequest) },
                variant = ButtonVariant.Tertiary,
                enabled = !state.busy,
            )
            ZillitButton(
                text = if (state.busy) str(S.desktop_pc_requesting) else str(S.av_send_request),
                onClick = { act(FundsAction.SubmitRequest) },
                enabled = canSubmit,
                loading = state.busy,
            )
        },
    ) {
        if (draft == null) return@ZillitDialogShell
        ZillitText(
            text = str(S.desktop_pc_request_topup_intro, float?.let(::floatLabel).orEmpty()),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitTextField(
            value = draft.amount,
            onValueChange = { act(FundsAction.EditRequest(it, draft.reason)) },
            label = "${str(S.amount)} *",
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            enabled = !state.busy,
            trailingContent = {
                ZillitText(
                    text = state.currencyOf(float?.currency),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = draft.reason,
            onValueChange = { act(FundsAction.EditRequest(draft.amount, it)) },
            label = "${str(S.reason)} *",
            placeholder = str(S.desktop_pc_eg_fuel_run),
            singleLine = false,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The web's Top-up History drawer: each step, who took it, when, and its reason and amount. */
@Composable
private fun TopUpHistoryDialog(
    state: CashUiState,
    topUp: CashTopUp?,
    fallbackCurrency: String?,
    onDismiss: () -> Unit,
) {
    val people = LocalCashPeople.current
    val currency = topUp?.currency ?: fallbackCurrency
    ZillitDialogShell(
        title = str(S.desktop_pc_topup_history),
        subtitle = topUp?.let { "${state.formatMoney(it.amount, currency)} · ${EpochDate.date(it.createdAt)}" },
        icon = ZillitIcons.Clock,
        visible = topUp != null,
        onDismiss = onDismiss,
    ) {
        topUp?.history.orEmpty().forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZillitText(
                        text = entry.action.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    ZillitText(
                        text = EpochDate.dateTime(entry.actionAt),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
                entry.actionBy?.let {
                    ZillitText(
                        text = people.nameOf(it),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                val note = listOfNotNull(
                    entry.reason?.takeIf(String::isNotBlank),
                    entry.amount?.let { state.formatMoney(it, currency) },
                ).joinToString(" · ")
                if (note.isNotEmpty()) {
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

/** `Float PC-001 — Set dressing`, as the web labels an entity. */
private fun floatLabel(float: CashFloat): String {
    val name = str(S.desktop_pc_float_label, float.requestNumber.ifBlank { float.id })
    return float.purpose?.takeIf(String::isNotBlank)?.let { "$name — $it" } ?: name
}

private val FLOAT_PICKER_WIDTH = 280.dp
